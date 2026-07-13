import java.io.BufferedInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// Downloads the pinned official Slang release archives, extracts the runtime library payload,
// and records (first run) or verifies (subsequent runs / CI) a per-platform manifest with
// SHA-256s. DESIGN.md §3.1 / §7 / M0. Packaging the payloads into natives jars is milestone M6.

val slangVersion = "2026.13"

// os-arch → official release asset name. The plain linux zips target the release builders'
// glibc; -glibc-2.27 / -2.28 variants exist upstream for older distros if ever needed.
val platformAssets = mapOf(
    "windows-x86_64" to "slang-$slangVersion-windows-x86_64.zip",
    "windows-aarch64" to "slang-$slangVersion-windows-aarch64.zip",
    "linux-x86_64" to "slang-$slangVersion-linux-x86_64.zip",
    "linux-aarch64" to "slang-$slangVersion-linux-aarch64.zip",
    "macos-x86_64" to "slang-$slangVersion-macos-x86_64.zip",
    "macos-aarch64" to "slang-$slangVersion-macos-aarch64.zip",
)

fun currentPlatform(): String {
    val osName = System.getProperty("os.name").lowercase()
    val os = when {
        "win" in osName -> "windows"
        "mac" in osName -> "macos"
        else -> "linux"
    }
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64" -> "x86_64"
        else -> error("Unsupported CPU architecture: $a")
    }
    return "$os-$arch"
}

fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

// Streaming SHA-256: archives contain multi-hundred-MB members (slang-llvm), so nothing in this
// task may buffer a whole archive or entry in memory.
fun fileSha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return toHex(md.digest())
}

// Zip archives flatten the macOS/Linux library symlinks (libslang-compiler.dylib →
// libslang-compiler.0.<ver>.dylib) into tiny text files holding the target name. Returns the
// target if the content looks like such a stub: a bare sibling filename (no slashes or control
// characters) with a shared-library extension.
fun symlinkTargetOrNull(bytes: ByteArray): String? {
    if (bytes.isEmpty() || bytes.size > 255) return null
    val text = String(bytes, Charsets.UTF_8)
    if (!text.matches(Regex("[A-Za-z0-9._+-]+"))) return null
    return if (text.endsWith(".dylib") || text.contains(".so") || text.endsWith(".dll")) text else null
}

// Decides where an archive entry lands in the normalized payload, or null to skip it.
//
// The runtime library set lives under lib/ on macOS/Linux but under bin/ on Windows (Windows
// lib/ holds only MSVC import libraries), and the slang-standard-module tree sits beside the
// libraries on both. Everything is normalized into a single payload lib/ directory because
// slang-compiler locates its companion libraries and standard modules relative to its own file.
// Executables (slangc, slangd), import libraries, debug info, docs, and cmake/pkgconfig metadata
// are not runtime payload and are skipped.
fun destinationFor(entryName: String): String? {
    val parts = entryName.split('/').filter { it.isNotEmpty() }
    require(".." !in parts) { "Refusing zip entry escaping payload dir: $entryName" }
    if (parts.none { it == "lib" || it == "bin" }) return null
    if (parts.any { it == "cmake" || it == "pkgconfig" }) return null
    val moduleIndex = parts.indexOfFirst { it.startsWith("slang-standard-module") }
    if (moduleIndex >= 0) return "lib/" + parts.subList(moduleIndex, parts.size).joinToString("/")
    val lower = parts.last().lowercase()
    if (lower.endsWith(".dwarf") || lower.endsWith(".pdb")
        || lower.endsWith(".lib") || lower.endsWith(".exp")) {
        return null
    }
    val isSharedLib = lower.endsWith(".dll") || lower.endsWith(".dylib") || ".so" in lower
    return if (isSharedLib || lower.endsWith(".slang-module")) "lib/${parts.last()}" else null
}

tasks.register("downloadNatives") {
    description = "Downloads pinned Slang release archive(s) and extracts the runtime payload. " +
        "Select platforms with -PnativesPlatform=current|all|<os-arch> (default: current)."
    notCompatibleWithConfigurationCache("ad-hoc network task; will become a proper task class in M6")

    doLast {
        val selector = (findProperty("nativesPlatform") as String?) ?: "current"
        val selected = when (selector) {
            "all" -> platformAssets.keys.toList()
            "current" -> listOf(currentPlatform())
            else -> listOf(selector).onEach {
                require(it in platformAssets) { "Unknown platform '$it'; expected one of ${platformAssets.keys}" }
            }
        }
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        for (platform in selected) {
            val asset = platformAssets.getValue(platform)
            val url = "https://github.com/shader-slang/slang/releases/download/v$slangVersion/$asset"
            val zipFile = layout.buildDirectory.file("downloads/$asset").get().asFile
            val payloadDir = layout.buildDirectory.dir("payload/$platform").get().asFile
            val manifestFile = file("manifests/$platform.json")

            if (!zipFile.exists()) {
                logger.lifecycle("Downloading $url")
                zipFile.parentFile.mkdirs()
                val response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofFile(zipFile.toPath()))
                check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
            }

            val zipSha = fileSha256(zipFile)

            // Trust-on-first-use: the first run records the manifest (which gets committed);
            // later runs — most importantly CI — verify against it.
            if (manifestFile.exists()) {
                val pinned = Regex("\"sha256\": \"([0-9a-f]{64})\"").find(manifestFile.readText())
                    ?.groupValues?.get(1)
                check(pinned == zipSha) {
                    "SHA-256 mismatch for $asset: manifest pins $pinned but download is $zipSha"
                }
                logger.lifecycle("$asset: sha256 verified against manifests/$platform.json")
            }

            payloadDir.deleteRecursively()
            val extracted = sortedMapOf<String, Pair<Long, String>>()
            val symlinks = sortedMapOf<String, String>() // payload-relative path -> sibling target
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val dest = destinationFor(entry.name) ?: continue

                    // Peek just enough to recognize a flattened-symlink stub, then stream the
                    // rest straight to disk.
                    val peek = ByteArray(256 + 1)
                    var peeked = 0
                    while (peeked < peek.size) {
                        val n = zip.read(peek, peeked, peek.size - peeked)
                        if (n < 0) break
                        peeked += n
                    }
                    if (peeked <= 255) {
                        val target = symlinkTargetOrNull(peek.copyOf(peeked))
                        if (target != null) {
                            symlinks[dest] = target
                            continue
                        }
                    }

                    val outFile = File(payloadDir, dest)
                    outFile.parentFile.mkdirs()
                    val md = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    outFile.outputStream().buffered().use { out ->
                        out.write(peek, 0, peeked)
                        md.update(peek, 0, peeked)
                        size += peeked
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            size += n
                        }
                    }
                    extracted[dest] = size to toHex(md.digest())
                }
            }
            check(extracted.isNotEmpty()) { "No runtime payload entries found in $asset" }

            // Re-materialize flattened symlinks so the payload behaves like the official layout
            // (falling back to a full copy where symlinks are unavailable, e.g. Windows without
            // developer mode extracting a posix payload).
            for ((linkPath, target) in symlinks) {
                val link = File(payloadDir, linkPath)
                val targetFile = File(link.parentFile, target)
                if (!targetFile.isFile) {
                    logger.warn("$asset: symlink $linkPath points at missing $target; skipping")
                    continue
                }
                link.parentFile.mkdirs()
                try {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), File(target).toPath())
                } catch (e: Exception) {
                    targetFile.copyTo(link)
                }
            }

            if (!manifestFile.exists()) {
                manifestFile.parentFile.mkdirs()
                manifestFile.writeText(buildString {
                    appendLine("{")
                    appendLine("  \"slangVersion\": \"$slangVersion\",")
                    appendLine("  \"platform\": \"$platform\",")
                    appendLine("  \"asset\": \"$asset\",")
                    appendLine("  \"url\": \"$url\",")
                    appendLine("  \"sha256\": \"$zipSha\",")
                    appendLine("  \"files\": [")
                    append(extracted.entries.joinToString(",\n") { (path, meta) ->
                        "    {\"path\": \"$path\", \"size\": ${meta.first}, \"sha256\": \"${meta.second}\"}"
                    })
                    appendLine()
                    appendLine("  ],")
                    appendLine("  \"symlinks\": [")
                    append(symlinks.entries.joinToString(",\n") { (path, target) ->
                        "    {\"path\": \"$path\", \"target\": \"$target\"}"
                    })
                    appendLine()
                    appendLine("  ]")
                    appendLine("}")
                })
                logger.lifecycle("$asset: recorded new manifest at manifests/$platform.json — review and commit it")
            }
            logger.lifecycle("$platform: ${extracted.size} files + ${symlinks.size} symlinks in ${payloadDir.relativeTo(rootDir)}")
        }
    }
}

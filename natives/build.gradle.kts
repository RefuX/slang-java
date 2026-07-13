import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.BufferedInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// Downloads the pinned official Slang release archives, extracts the runtime library payload,
// and records (first run) or verifies (subsequent runs / CI) a per-platform manifest with
// SHA-256s. The per-platform subprojects (:natives:<os>-<arch>) package those payloads as the
// publishable io.github.refux:slang-java-natives-<os>-<arch> artifacts. DESIGN.md §3.1/§7/§16.

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
    // slang-llvm (~100 MB) backs only the CPU / host-callable targets, which are out of scope
    // (we produce shader bytes). slang-compiler dlopens it lazily, so omitting it keeps the
    // SPIR-V/HLSL/GLSL/WGSL/Metal targets working while cutting the payload by two-thirds.
    if (lower.startsWith("slang-llvm") || lower.startsWith("libslang-llvm")) return null
    val isSharedLib = lower.endsWith(".dll") || lower.endsWith(".dylib") || ".so" in lower
    return if (isSharedLib || lower.endsWith(".slang-module")) "lib/${parts.last()}" else null
}

// Downloads (cached), verifies against the committed manifest (or records it on first run),
// and extracts one platform's runtime payload to build/payload/<platform>/.
fun downloadAndExtract(platform: String) {
    val asset = platformAssets.getValue(platform)
    val url = "https://github.com/shader-slang/slang/releases/download/v$slangVersion/$asset"
    val zipFile = layout.buildDirectory.file("downloads/$asset").get().asFile
    val payloadDir = layout.buildDirectory.dir("payload/$platform").get().asFile
    val manifestFile = file("manifests/$platform.json")

    if (!zipFile.exists()) {
        logger.lifecycle("Downloading $url")
        zipFile.parentFile.mkdirs()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(
            HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofFile(zipFile.toPath()))
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
    }

    val zipSha = fileSha256(zipFile)

    // Trust-on-first-use: the first run records the manifest (which gets committed);
    // later runs — most importantly CI and releases — verify against it.
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

// One download task per platform (the per-platform subprojects depend on these) …
platformAssets.keys.forEach { platform ->
    tasks.register("downloadNatives-$platform") {
        description = "Downloads and extracts the $platform Slang payload."
        notCompatibleWithConfigurationCache("ad-hoc network task")
        doLast { downloadAndExtract(platform) }
    }
}

// … plus the aggregate used by CI and by hand: -PnativesPlatform=current|all|<os-arch>.
tasks.register("downloadNatives") {
    description = "Downloads pinned Slang release archive(s) and extracts the runtime payload. " +
        "Select platforms with -PnativesPlatform=current|all|<os-arch> (default: current)."
    notCompatibleWithConfigurationCache("ad-hoc network task")
    doLast {
        val selector = (findProperty("nativesPlatform") as String?) ?: "current"
        val selected = when (selector) {
            "all" -> platformAssets.keys.toList()
            "current" -> listOf(currentPlatform())
            else -> listOf(selector).onEach {
                require(it in platformAssets) { "Unknown platform '$it'; expected one of ${platformAssets.keys}" }
            }
        }
        selected.forEach { downloadAndExtract(it) }
    }
}

// ---------------------------------------------------------------------------------------------
// Single publishable module io.github.refux:slang-java-natives with one classifier artifact per
// platform (slang-java-natives-<ver>-<os>-<arch>.jar), LWJGL-style. Each classifier jar carries
// that platform's payload under META-INF/natives/<os>/<arch>/ plus an index.txt the runtime
// loader reads to extract it (symlinks are NOT jarred — archiving would inflate them into full
// copies — they are recreated from the index at extraction time).
// ---------------------------------------------------------------------------------------------
apply(plugin = "java-library")
apply(plugin = "com.vanniktech.maven.publish")

extensions.configure<BasePluginExtension>("base") {
    archivesName = "slang-java-natives"
}

// One classifier jar per platform. Returns the jar tasks so the publication can attach them.
// Platforms whose manifest hasn't been recorded yet are skipped (so `downloadNatives` can run
// to create them on a fresh checkout, or when regenerating them); the release always records
// all six first.
val classifierJars = platformAssets.keys.mapNotNull { platform ->
    val os = platform.substringBeforeLast('-')
    val arch = platform.substringAfterLast('-')
    val manifestFile = file("manifests/$platform.json")
    if (!manifestFile.exists()) {
        logger.info("natives: no manifest for $platform yet; skipping its classifier jar")
        return@mapNotNull null
    }

    @Suppress("UNCHECKED_CAST")
    val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    val manifestFiles = manifest["files"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    val manifestSymlinks = manifest["symlinks"] as List<Map<String, Any>>

    // The loader's extraction index: header line, then one line per file / symlink.
    val generateIndex = tasks.register("generateNativesIndex-$platform") {
        inputs.file(manifestFile)
        val out = layout.buildDirectory.file("natives-index/$platform/index.txt")
        outputs.file(out)
        doLast {
            val lines = mutableListOf("slang ${manifest["slangVersion"]} ${manifest["sha256"]}")
            manifestFiles.forEach { lines += "F ${it["path"]}" }
            manifestSymlinks.forEach { lines += "L ${it["path"]} ${it["target"]}" }
            out.get().asFile.parentFile.mkdirs()
            out.get().asFile.writeText(lines.joinToString("\n") + "\n")
        }
    }

    tasks.register<Jar>("nativesJar-$platform") {
        archiveClassifier = platform
        dependsOn("downloadNatives-$platform")
        from(layout.buildDirectory.dir("payload/$platform")) {
            into("META-INF/natives/$os/$arch")
            // Symlinks would be archived as full copies of their targets; the loader
            // recreates them from the index instead.
            manifestSymlinks.forEach { exclude(it["path"] as String) }
        }
        from(generateIndex) {
            into("META-INF/natives/$os/$arch")
        }
    }
}

// Attach the classifier jars to the publication vanniktech creates (it also produces the empty
// main + sources + javadoc jars Central requires, and signs them all).
afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications.named<MavenPublication>("maven") {
            classifierJars.forEach { artifact(it) }
        }
    }
}

extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.refux", "slang-java-natives", version.toString())
    configure(JavaLibrary(JavadocJar.Empty(), sourcesJar = true))

    pom {
        name = "slang-java-natives"
        description = "Official Slang compiler binaries (v$slangVersion), repackaged unmodified " +
            "as one classifier artifact per platform for the io.github.refux:slang-java bindings."
        inceptionYear = "2026"
        url = "https://github.com/RefuX/slang-java"
        licenses {
            license {
                name = "Apache-2.0 WITH LLVM-exception"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "RefuX"
                name = "James Roome"
                url = "https://github.com/RefuX"
            }
        }
        scm {
            url = "https://github.com/RefuX/slang-java"
            connection = "scm:git:git://github.com/RefuX/slang-java.git"
            developerConnection = "scm:git:ssh://git@github.com/RefuX/slang-java.git"
        }
    }
}

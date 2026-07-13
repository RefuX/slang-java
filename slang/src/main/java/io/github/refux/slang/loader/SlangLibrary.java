package io.github.refux.slang.loader;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * Locates and loads the Slang compiler shared library and exposes a {@link SymbolLookup} over it.
 *
 * <p>Resolution order (first hit wins), per DESIGN.md §7:
 * <ol>
 *   <li>System property {@value #PROPERTY_LIBRARY_PATH} — a directory containing the Slang
 *       library set (an official release {@code lib/} payload, or a local Slang build's
 *       {@code build/<config>/lib/} directory).</li>
 *   <li>Environment variable {@value #ENV_LIBRARY_PATH} — same meaning.</li>
 *   <li><i>(M6, not yet implemented)</i> natives jar on the class path, extracted to a cache.</li>
 *   <li>The platform's default library search path, trying the current library name
 *       {@code slang-compiler} first and the legacy name {@code slang} second.</li>
 * </ol>
 *
 * <p>The library is loaded exactly once, into {@link Arena#global()}: Slang is not designed to be
 * unloaded from a process, so the binding never attempts it.
 */
public final class SlangLibrary {
    public static final String PROPERTY_LIBRARY_PATH = "io.github.refux.slang.libraryPath";
    public static final String ENV_LIBRARY_PATH = "SLANG_JAVA_LIBRARY_PATH";

    /**
     * The Slang release this binding is generated against and tested with. Newer libraries are
     * expected to work (Slang's public ABI is append-only); older ones are not.
     */
    public static final String PINNED_SLANG_VERSION = "2026.13";

    private static final class Holder {
        static final SlangLibrary INSTANCE = load();
    }

    private final SymbolLookup lookup;
    private final String location;

    private SlangLibrary(SymbolLookup lookup, String location) {
        this.lookup = lookup;
        this.location = location;
    }

    /** Returns the process-wide instance, loading the native library on first use. */
    public static SlangLibrary get() {
        return Holder.INSTANCE;
    }

    /**
     * Returns the address of an exported symbol, failing with a descriptive error if the loaded
     * library does not provide it (e.g. the library predates the pinned Slang version).
     */
    public MemorySegment find(String symbol) {
        return lookup.find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError(
                        "Symbol '" + symbol + "' not found in Slang library loaded from " + location
                                + " (binding is pinned to Slang " + PINNED_SLANG_VERSION
                                + "; older libraries lack newer symbols)"));
    }

    /** A human-readable description of where the library was loaded from, for diagnostics. */
    public String location() {
        return location;
    }

    private static SlangLibrary load() {
        String dir = System.getProperty(PROPERTY_LIBRARY_PATH);
        if (dir == null || dir.isBlank()) {
            dir = System.getenv(ENV_LIBRARY_PATH);
        }
        if (dir != null && !dir.isBlank()) {
            Path file = resolveInDirectory(Path.of(dir));
            return new SlangLibrary(SymbolLookup.libraryLookup(file, Arena.global()), file.toString());
        }
        // Fall back to the platform's default search path (PATH / LD_LIBRARY_PATH / DYLD_*).
        for (String name : new String[] {"slang-compiler", "slang"}) {
            try {
                String mapped = System.mapLibraryName(name);
                return new SlangLibrary(SymbolLookup.libraryLookup(mapped, Arena.global()), "system:" + mapped);
            } catch (IllegalArgumentException notFound) {
                // Try the next candidate name.
            }
        }
        throw new UnsatisfiedLinkError("Could not locate the Slang compiler library (slang-compiler). Point -D"
                + PROPERTY_LIBRARY_PATH + " (or $" + ENV_LIBRARY_PATH + ") at a directory "
                + "containing the Slang libraries, e.g. the lib/ directory of an official "
                + "slang-" + PINNED_SLANG_VERSION + " release archive.");
    }

    /**
     * Picks the Slang compiler library file inside {@code dir}. Tries the exact platform names
     * first ({@code libslang-compiler.dylib}, {@code slang-compiler.dll}, …, then the legacy
     * {@code slang} names), and falls back to version-suffixed files such as
     * {@code libslang-compiler.0.2026.13.dylib} — official archives may not preserve the
     * unversioned symlink. The prefix match is written so companion libraries
     * ({@code libslang-glslang}, {@code libslang-rt}, …) can never be picked up by mistake.
     */
    private static Path resolveInDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new UnsatisfiedLinkError("Slang library path is not a directory: " + dir);
        }
        for (String name : new String[] {System.mapLibraryName("slang-compiler"), System.mapLibraryName("slang")}) {
            Path exact = dir.resolve(name);
            if (Files.exists(exact) && isPlausibleLibraryFile(exact)) {
                return exact;
            }
        }
        for (String prefix : new String[] {
            // "libslang-compiler." / "slang-compiler." — never matches libslang-glslang etc.
            stripSuffixes(System.mapLibraryName("slang-compiler")) + ".",
            stripSuffixes(System.mapLibraryName("slang")) + "."
        }) {
            Optional<Path> versioned = findVersioned(dir, prefix);
            if (versioned.isPresent()) {
                return versioned.get();
            }
        }
        throw new UnsatisfiedLinkError("No Slang compiler library (slang-compiler / slang) found in directory: " + dir);
    }

    /** Strips the platform shared-library extension, keeping any "lib" prefix. */
    private static String stripSuffixes(String mappedName) {
        int dot = mappedName.lastIndexOf('.');
        return dot < 0 ? mappedName : mappedName.substring(0, dot);
    }

    private static Optional<Path> findVersioned(Path dir, String prefix) {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && looksLikeSharedLibrary(n);
                    })
                    .filter(SlangLibrary::isPlausibleLibraryFile)
                    // Deterministic choice if several versions are present: highest name wins.
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to scan Slang library directory " + dir + ": " + e);
        }
    }

    /**
     * Rejects files that cannot possibly be a shared library — chiefly the tiny text stubs that
     * zip extraction produces from macOS/Linux library symlinks (e.g. a 33-byte
     * {@code libslang-compiler.dylib} whose content is just the versioned target's name). Real
     * symlinks pass because {@link Files#size} follows them; broken ones fail and are skipped.
     */
    private static boolean isPlausibleLibraryFile(Path p) {
        try {
            return Files.size(p) >= 4096;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean looksLikeSharedLibrary(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".dwarf") || n.endsWith(".pdb") || n.endsWith(".lib") || n.endsWith(".exp")) {
            return false; // debug-info and import-library siblings, e.g. libslang-compiler.….dylib.dwarf
        }
        return n.endsWith(".dylib") || n.endsWith(".dll") || n.contains(".so");
    }
}

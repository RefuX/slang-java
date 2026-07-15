package io.github.refux.slang;

import io.github.refux.slang.ffi.Marshal;
import io.github.refux.slang.ffi.PreprocessorMacroDesc;
import io.github.refux.slang.ffi.SessionDesc;
import io.github.refux.slang.ffi.TargetDesc;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Configures and creates a compilation {@link Session}: code-generation targets, search paths
 * for {@code import}/{@code #include}, preprocessor defines, and an optional diagnostics
 * consumer for warnings. Slang copies all of it during {@code createSession}, so nothing built
 * here outlives the {@link #create()} call.
 */
public final class SessionBuilder {
    private final GlobalSession global;
    private final List<TargetOptions> targets = new ArrayList<>();
    private final List<Path> searchPaths = new ArrayList<>();
    private final Map<String, String> defines = new LinkedHashMap<>();
    private Consumer<String> onDiagnostics;
    private SlangFileSystem fileSystem;

    SessionBuilder(GlobalSession global) {
        this.global = global;
    }

    /** Adds a code-generation target with default options. */
    public SessionBuilder target(CompileTarget target) {
        return target(target, options -> {});
    }

    /** Adds a code-generation target, e.g. {@code target(SPIRV, t -> t.profile("spirv_1_5"))}. */
    public SessionBuilder target(CompileTarget target, Consumer<TargetOptions> configure) {
        TargetOptions options = new TargetOptions(target);
        configure.accept(options);
        targets.add(options);
        return this;
    }

    /** Adds directories used to resolve {@code import}s and {@code #include}s. */
    public SessionBuilder searchPath(Path... paths) {
        Collections.addAll(searchPaths, paths);
        return this;
    }

    /** Adds a preprocessor macro visible to all code loaded in the session. */
    public SessionBuilder define(String name, String value) {
        defines.put(name, value);
        return this;
    }

    /**
     * Receives the compiler's diagnostics text for operations that succeed with warnings
     * (compilation failures throw {@link SlangCompileException} instead). Without a consumer,
     * warnings are dropped.
     */
    public SessionBuilder onDiagnostics(Consumer<String> consumer) {
        this.onDiagnostics = consumer;
        return this;
    }

    /**
     * Resolves the session's {@code import}s/{@code #include}s through Java instead of the OS
     * file system — see {@link SlangFileSystem} for ready-made map- and directory-backed
     * implementations.
     */
    public SessionBuilder fileSystem(SlangFileSystem fileSystem) {
        this.fileSystem = fileSystem;
        return this;
    }

    /** Creates the session. At least one {@link #target} is required. */
    public Session create() {
        if (targets.isEmpty()) {
            throw new IllegalStateException("a session needs at least one target(...)");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment targetArray = TargetDesc.allocateArray(arena, targets.size());
            for (int i = 0; i < targets.size(); i++) {
                MemorySegment desc = TargetDesc.element(targetArray, i);
                TargetOptions options = targets.get(i);
                TargetDesc.setFormat(desc, options.target().value());
                if (options.profileName != null) {
                    int profile = global.findProfile(options.profileName);
                    if (profile == 0) {
                        throw new IllegalArgumentException("unknown profile: " + options.profileName);
                    }
                    TargetDesc.setProfile(desc, profile);
                }
                if (options.flags != null) {
                    TargetDesc.setFlags(desc, options.flags);
                }
                if (options.forceGlslScalarBufferLayout != null) {
                    TargetDesc.setForceGlslScalarBufferLayout(desc, options.forceGlslScalarBufferLayout);
                }
            }

            MemorySegment sessionDesc = SessionDesc.allocate(arena);
            SessionDesc.setTargets(sessionDesc, targetArray, targets.size());
            if (!searchPaths.isEmpty()) {
                String[] paths = searchPaths.stream().map(Path::toString).toArray(String[]::new);
                SessionDesc.setSearchPaths(sessionDesc, Marshal.utf8PointerArray(arena, paths), paths.length);
            }
            if (!defines.isEmpty()) {
                MemorySegment macros = PreprocessorMacroDesc.allocateArray(arena, defines.size());
                int i = 0;
                for (Map.Entry<String, String> define : defines.entrySet()) {
                    PreprocessorMacroDesc.set(
                            macros, i++, arena.allocateFrom(define.getKey()), arena.allocateFrom(define.getValue()));
                }
                SessionDesc.setPreprocessorMacros(sessionDesc, macros, defines.size());
            }

            io.github.refux.slang.ffi.JavaFileSystem nativeFileSystem = null;
            if (fileSystem != null) {
                SlangFileSystem fs = fileSystem;
                nativeFileSystem = new io.github.refux.slang.ffi.JavaFileSystem() {
                    @Override
                    protected byte[] load(String path) throws Exception {
                        return fs.loadFile(path);
                    }
                };
                SessionDesc.setFileSystem(sessionDesc, nativeFileSystem.segment());
            }
            try {
                return new Session(global, global.ffi().createSession(sessionDesc), onDiagnostics);
            } finally {
                if (nativeFileSystem != null) {
                    // The session add-refed it during creation; drop the creation reference so
                    // the object's lifetime follows the session's.
                    nativeFileSystem.release();
                }
            }
        }
    }
}

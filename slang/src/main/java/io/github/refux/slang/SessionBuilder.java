package io.github.refux.slang;

import io.github.refux.slang.ffi.Marshal;
import io.github.refux.slang.ffi.PreprocessorMacroDesc;
import io.github.refux.slang.ffi.SessionDesc;
import io.github.refux.slang.ffi.TargetDesc;
import io.github.refux.slang.ffi.gen.CompilerOptionName;
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
 * for {@code import}/{@code #include}, preprocessor defines, compiler options, and an optional
 * diagnostics consumer for warnings. Slang copies all of it during {@code createSession}, so
 * nothing built here outlives the {@link #create()} call.
 *
 * <p>Compiler options set here apply to every target; a target's own
 * {@link TargetOptions#option(int, int) options} override them.
 */
public final class SessionBuilder {
    private final GlobalSession global;
    private final List<TargetOptions> targets = new ArrayList<>();
    private final List<Path> searchPaths = new ArrayList<>();
    private final Map<String, String> defines = new LinkedHashMap<>();
    private final OptionList compilerOptions = new OptionList();
    private Consumer<String> onDiagnostics;
    private SlangFileSystem fileSystem;
    private MatrixLayout matrixLayout;

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
     * Sets how this session lays out matrices, overriding the {@code SessionDesc} header default of
     * {@link MatrixLayout#ROW_MAJOR}.
     *
     * <p>Set it to {@link MatrixLayout#COLUMN_MAJOR} to match what the {@code slangc} command line
     * produces. A project that compiles some shaders offline with {@code slangc} and others at run
     * time through this library and does NOT set this gets silently transposed matrices in whichever
     * half it compiled here — see {@link MatrixLayout}.
     */
    public SessionBuilder matrixLayout(MatrixLayout layout) {
        this.matrixLayout = layout;
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

    /** How hard to optimize generated code; Slang's default is {@link OptimizationLevel#DEFAULT}. */
    public SessionBuilder optimization(OptimizationLevel level) {
        return option(CompilerOptionName.Optimization, Enums.raw(level, level.value()));
    }

    /** How much debug information to emit; Slang's default is {@link DebugInfoLevel#NONE}. */
    public SessionBuilder debugInfo(DebugInfoLevel level) {
        return option(CompilerOptionName.DebugInformation, Enums.raw(level, level.value()));
    }

    /**
     * Turns warnings into errors, so code that warns fails to load with
     * {@link SlangCompileException}: every warning when called with no arguments, otherwise the
     * given ones, by code or name (e.g. {@code "30081"}).
     */
    public SessionBuilder warningsAsErrors(String... warnings) {
        return option(CompilerOptionName.WarningsAsErrors, warnings.length == 0 ? "all" : String.join(",", warnings));
    }

    /** Silences warnings, by code or name (e.g. {@code "30081"}). */
    public SessionBuilder disableWarnings(String... warnings) {
        if (warnings.length == 0) {
            throw new IllegalArgumentException("name at least one warning to disable");
        }
        return option(CompilerOptionName.DisableWarnings, String.join(",", warnings));
    }

    /**
     * The Slang language version to compile as, e.g. {@code 2026} — a {@code SlangLanguageVersion}
     * value. The global session's default is 2025.
     */
    public SessionBuilder languageVersion(int version) {
        return option(CompilerOptionName.LanguageVersion, version);
    }

    /**
     * Requires a capability by name (e.g. {@code "spirv_1_5"}, {@code "SPV_KHR_ray_query"}); repeat
     * for several.
     *
     * @throws IllegalArgumentException if this Slang build does not know the name
     */
    public SessionBuilder capability(String name) {
        int capability = global.findCapability(name);
        if (capability == 0) {
            throw new IllegalArgumentException("unknown capability: " + name);
        }
        return option(CompilerOptionName.Capability, capability);
    }

    /** How bitfield members are packed; Slang's default is {@link BitfieldPacking#DEFAULT}. */
    public SessionBuilder bitfieldPacking(BitfieldPacking rules) {
        return option(CompilerOptionName.BitfieldPackingRules, Enums.raw(rules, rules.value()));
    }

    /**
     * Sets a compiler option by its raw {@code slang::CompilerOptionName} value, as found in
     * {@code io.github.refux.slang.ffi.gen.CompilerOptionName} — the escape hatch for options without a
     * method of their own. The value must be the kind the option expects (its comment in
     * {@code slang.h} says): this overload for int and enum options.
     *
     * <p>Options that {@code TargetDesc} also carries as fields — the floating-point mode, line
     * directive mode and GLSL scalar layout — are reapplied from each target after the session's
     * options, so set those per target with {@link TargetOptions#option(int, int)} instead.
     */
    public SessionBuilder option(int name, int value) {
        return option(name, value, 0);
    }

    /** As {@link #option(int, int)}, for bool options. */
    public SessionBuilder option(int name, boolean value) {
        return option(name, value ? 1 : 0);
    }

    /** As {@link #option(int, int)}, for the few options that take two ints. */
    public SessionBuilder option(int name, int value0, int value1) {
        compilerOptions.add(name, value0, value1);
        return this;
    }

    /** As {@link #option(int, int)}, for string options. */
    public SessionBuilder option(int name, String value) {
        compilerOptions.add(name, value);
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
                if (options.compilerOptions.size() > 0) {
                    TargetDesc.setCompilerOptionEntries(
                            desc, options.compilerOptions.write(arena), options.compilerOptions.size());
                }
            }

            MemorySegment sessionDesc = SessionDesc.allocate(arena);
            SessionDesc.setTargets(sessionDesc, targetArray, targets.size());
            if (matrixLayout != null) {
                SessionDesc.setDefaultMatrixLayoutMode(sessionDesc, matrixLayout.value());
            }
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
            if (compilerOptions.size() > 0) {
                SessionDesc.setCompilerOptionEntries(sessionDesc, compilerOptions.write(arena), compilerOptions.size());
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

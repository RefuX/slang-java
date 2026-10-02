package io.github.refux.slang.ffi;

import io.github.refux.slang.ffi.gen.SlangMatrixLayoutMode;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Convenience over the generated {@code ffi.gen.SessionDesc} layout (offsets clang-verified,
 * size 96): allocation with the C++ header's defaults plus the pointer/count pair setters.
 *
 * <p>Lifetime note (verified empirically by {@code CompilePipelineTest}): Slang copies the
 * descriptor's contents — including target descs, search paths, and preprocessor macro strings —
 * into the session during {@code IGlobalSession::createSession}, so the arena holding the
 * descriptor and everything it points at may be freed as soon as the call returns.
 */
public final class SessionDesc {
    private SessionDesc() {}

    public static final long SIZE = io.github.refux.slang.ffi.gen.SessionDesc.SIZE;

    /** Allocates a descriptor with the C++ header's default field values. */
    public static MemorySegment allocate(Arena arena) {
        MemorySegment desc = io.github.refux.slang.ffi.gen.SessionDesc.allocate(arena);
        io.github.refux.slang.ffi.gen.SessionDesc.setDefaultMatrixLayoutMode(
                desc, SlangMatrixLayoutMode.SLANG_MATRIX_LAYOUT_ROW_MAJOR);
        return desc; // structureSize prefilled by the generated allocate(); the rest is zero
    }

    /** Points {@code targets} at a {@link TargetDesc#allocateArray} array of {@code count}. */
    public static void setTargets(MemorySegment desc, MemorySegment targetArray, long count) {
        io.github.refux.slang.ffi.gen.SessionDesc.setTargets(desc, targetArray);
        io.github.refux.slang.ffi.gen.SessionDesc.setTargetCount(desc, count);
    }

    /** Points {@code searchPaths} at a {@link Marshal#utf8PointerArray} of {@code count}. */
    public static void setSearchPaths(MemorySegment desc, MemorySegment pathArray, long count) {
        io.github.refux.slang.ffi.gen.SessionDesc.setSearchPaths(desc, pathArray);
        io.github.refux.slang.ffi.gen.SessionDesc.setSearchPathCount(desc, count);
    }

    /** Points {@code preprocessorMacros} at a {@link PreprocessorMacroDesc} array of {@code count}. */
    public static void setPreprocessorMacros(MemorySegment desc, MemorySegment macroArray, long count) {
        io.github.refux.slang.ffi.gen.SessionDesc.setPreprocessorMacros(desc, macroArray);
        io.github.refux.slang.ffi.gen.SessionDesc.setPreprocessorMacroCount(desc, count);
    }

    /**
     * Sets how the session lays out matrices. The header default this descriptor is allocated with
     * is {@code SLANG_MATRIX_LAYOUT_ROW_MAJOR}, which is NOT what the {@code slangc} command line
     * uses — see {@code MatrixLayout} for what that costs a project that compiles both ways.
     */
    public static void setDefaultMatrixLayoutMode(MemorySegment desc, int mode) {
        io.github.refux.slang.ffi.gen.SessionDesc.setDefaultMatrixLayoutMode(desc, mode);
    }

    /**
     * Points {@code compilerOptionEntries} at a {@link CompilerOptionEntry} array of {@code count}.
     * Every target inherits these, and its own entries override them.
     */
    public static void setCompilerOptionEntries(MemorySegment desc, MemorySegment entryArray, int count) {
        io.github.refux.slang.ffi.gen.SessionDesc.setCompilerOptionEntries(desc, entryArray);
        io.github.refux.slang.ffi.gen.SessionDesc.setCompilerOptionEntryCount(desc, count);
    }

    /**
     * Points {@code fileSystem} at an {@code ISlangFileSystem} COM pointer (e.g. a
     * {@link JavaFileSystem}'s {@code segment()}); the session add-refs it during creation.
     */
    public static void setFileSystem(MemorySegment desc, MemorySegment fileSystem) {
        io.github.refux.slang.ffi.gen.SessionDesc.setFileSystem(desc, fileSystem);
    }
}

package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;

/**
 * Memory layout for {@code struct slang::SessionDesc} (slang.h). Offsets, size (96), and defaults
 * are compiler-verified by {@code tools/abi-probe.cpp}; see that file for the field table.
 *
 * <p>Lifetime note (verified empirically by {@code CompilePipelineTest}): Slang copies the
 * descriptor's contents — including target descs, search paths, and preprocessor macro strings —
 * into the session during {@code IGlobalSession::createSession}, so the arena holding the
 * descriptor and everything it points at may be freed as soon as the call returns.
 *
 * <p>M1 hand-written; the M2 generator derives this from libclang instead (DESIGN.md §9).
 */
public final class SessionDesc {
    private SessionDesc() {}

    /** {@code SLANG_MATRIX_LAYOUT_ROW_MAJOR}, the C++ header's default (probed value 1). */
    public static final int MATRIX_LAYOUT_ROW_MAJOR = 1;

    public static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
                    JAVA_LONG.withName("structureSize"),
                    ADDRESS.withName("targets"),
                    JAVA_LONG.withName("targetCount"),
                    JAVA_INT.withName("flags"),
                    JAVA_INT.withName("defaultMatrixLayoutMode"),
                    ADDRESS.withName("searchPaths"),
                    JAVA_LONG.withName("searchPathCount"),
                    ADDRESS.withName("preprocessorMacros"),
                    JAVA_LONG.withName("preprocessorMacroCount"),
                    ADDRESS.withName("fileSystem"),
                    JAVA_BOOLEAN.withName("enableEffectAnnotations"),
                    JAVA_BOOLEAN.withName("allowGLSLSyntax"),
                    MemoryLayout.paddingLayout(6),
                    ADDRESS.withName("compilerOptionEntries"),
                    JAVA_INT.withName("compilerOptionEntryCount"),
                    JAVA_BOOLEAN.withName("skipSPIRVValidation"),
                    MemoryLayout.paddingLayout(3))
            .withName("SessionDesc");

    private static final long OFFSET_STRUCTURE_SIZE = offset("structureSize");
    private static final long OFFSET_TARGETS = offset("targets");
    private static final long OFFSET_TARGET_COUNT = offset("targetCount");
    private static final long OFFSET_MATRIX_LAYOUT = offset("defaultMatrixLayoutMode");
    private static final long OFFSET_SEARCH_PATHS = offset("searchPaths");
    private static final long OFFSET_SEARCH_PATH_COUNT = offset("searchPathCount");
    private static final long OFFSET_MACROS = offset("preprocessorMacros");
    private static final long OFFSET_MACRO_COUNT = offset("preprocessorMacroCount");

    static {
        if (LAYOUT.byteSize() != 96
                || offset("fileSystem") != 64
                || offset("compilerOptionEntries") != 80
                || offset("skipSPIRVValidation") != 92) {
            throw new IllegalStateException("SessionDesc layout drifted from the probed C layout");
        }
    }

    private static long offset(String field) {
        return LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    /** Allocates a descriptor with the C++ header's default field values. */
    public static MemorySegment allocate(Arena arena) {
        MemorySegment desc = arena.allocate(LAYOUT);
        desc.set(JAVA_LONG, OFFSET_STRUCTURE_SIZE, LAYOUT.byteSize());
        desc.set(JAVA_INT, OFFSET_MATRIX_LAYOUT, MATRIX_LAYOUT_ROW_MAJOR);
        return desc; // remaining defaults are zero; arena allocations are zero-initialized
    }

    /** Points {@code targets} at a {@link TargetDesc#allocateArray} array of {@code count}. */
    public static void setTargets(MemorySegment desc, MemorySegment targetArray, long count) {
        desc.set(ADDRESS, OFFSET_TARGETS, targetArray);
        desc.set(JAVA_LONG, OFFSET_TARGET_COUNT, count);
    }

    /** Points {@code searchPaths} at a {@link Marshal#utf8PointerArray} of {@code count}. */
    public static void setSearchPaths(MemorySegment desc, MemorySegment pathArray, long count) {
        desc.set(ADDRESS, OFFSET_SEARCH_PATHS, pathArray);
        desc.set(JAVA_LONG, OFFSET_SEARCH_PATH_COUNT, count);
    }

    /** Points {@code preprocessorMacros} at a {@link PreprocessorMacroDesc} array of {@code count}. */
    public static void setPreprocessorMacros(MemorySegment desc, MemorySegment macroArray, long count) {
        desc.set(ADDRESS, OFFSET_MACROS, macroArray);
        desc.set(JAVA_LONG, OFFSET_MACRO_COUNT, count);
    }
}

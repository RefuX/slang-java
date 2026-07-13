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
 * Memory layout for {@code struct slang::TargetDesc} (slang.h) — one code-generation target of a
 * session. Offsets, sizes, and default values are compiler-verified by
 * {@code tools/abi-probe.cpp} (size 48, identical on all supported 64-bit ABIs):
 *
 * <pre>
 * off  0  size_t                  structureSize   = 48
 * off  8  SlangCompileTarget      format          = SLANG_TARGET_UNKNOWN (0)
 * off 12  SlangProfileID          profile         = SLANG_PROFILE_UNKNOWN (0)
 * off 16  SlangTargetFlags        flags           = kDefaultTargetFlags (1024)
 * off 20  SlangFloatingPointMode  floatingPointMode = DEFAULT (0)
 * off 24  SlangLineDirectiveMode  lineDirectiveMode = DEFAULT (0)
 * off 28  bool                    forceGLSLScalarBufferLayout = false   (+3 padding)
 * off 32  const CompilerOptionEntry* compilerOptionEntries = null
 * off 40  uint32_t                compilerOptionEntryCount = 0          (+4 padding)
 * </pre>
 *
 * M1 hand-written; the M2 generator derives this from libclang instead (DESIGN.md §9).
 */
public final class TargetDesc {
    private TargetDesc() {}

    /** Default {@code flags} value ({@code kDefaultTargetFlags}), read via the ABI probe. */
    public static final int DEFAULT_TARGET_FLAGS = 1024;

    public static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
                    JAVA_LONG.withName("structureSize"),
                    JAVA_INT.withName("format"),
                    JAVA_INT.withName("profile"),
                    JAVA_INT.withName("flags"),
                    JAVA_INT.withName("floatingPointMode"),
                    JAVA_INT.withName("lineDirectiveMode"),
                    JAVA_BOOLEAN.withName("forceGLSLScalarBufferLayout"),
                    MemoryLayout.paddingLayout(3),
                    ADDRESS.withName("compilerOptionEntries"),
                    JAVA_INT.withName("compilerOptionEntryCount"),
                    MemoryLayout.paddingLayout(4))
            .withName("TargetDesc");

    private static final long OFFSET_STRUCTURE_SIZE = offset("structureSize");
    private static final long OFFSET_FORMAT = offset("format");
    private static final long OFFSET_PROFILE = offset("profile");
    private static final long OFFSET_FLAGS = offset("flags");

    static {
        if (LAYOUT.byteSize() != 48 || offset("compilerOptionEntries") != 32) {
            throw new IllegalStateException("TargetDesc layout drifted from the probed C layout");
        }
    }

    private static long offset(String field) {
        return LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    /** Allocates one descriptor with the C++ header's default field values. */
    public static MemorySegment allocate(Arena arena) {
        return initDefaults(arena.allocate(LAYOUT));
    }

    /** Allocates a contiguous {@code TargetDesc[count]} with defaults in every element. */
    public static MemorySegment allocateArray(Arena arena, int count) {
        MemorySegment array = arena.allocate(LAYOUT.byteSize() * count, LAYOUT.byteAlignment());
        for (int i = 0; i < count; i++) {
            initDefaults(element(array, i));
        }
        return array;
    }

    /** Returns the {@code index}-th element of an array allocated by {@link #allocateArray}. */
    public static MemorySegment element(MemorySegment array, int index) {
        return array.asSlice(LAYOUT.byteSize() * index, LAYOUT.byteSize());
    }

    private static MemorySegment initDefaults(MemorySegment desc) {
        desc.set(JAVA_LONG, OFFSET_STRUCTURE_SIZE, LAYOUT.byteSize());
        desc.set(JAVA_INT, OFFSET_FLAGS, DEFAULT_TARGET_FLAGS);
        return desc; // remaining defaults are zero; arena allocations are zero-initialized
    }

    /** Sets {@code format} to a {@link SlangCompileTarget} value. */
    public static void setFormat(MemorySegment desc, int compileTarget) {
        desc.set(JAVA_INT, OFFSET_FORMAT, compileTarget);
    }

    /** Sets {@code profile} to an id from {@link IGlobalSession#findProfile(String)}. */
    public static void setProfile(MemorySegment desc, int profileId) {
        desc.set(JAVA_INT, OFFSET_PROFILE, profileId);
    }
}

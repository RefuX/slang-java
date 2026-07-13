package io.github.refux.slang.ffi;

import io.github.refux.slang.ffi.gen.SlangMiscConstants;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Convenience over the generated {@code ffi.gen.TargetDesc} layout (offsets clang-verified, size
 * 48): allocation with the C++ header's default field values, plus the setters the compile
 * pipeline needs. The non-obvious default is {@code flags = kDefaultTargetFlags}
 * (= {@code SLANG_TARGET_FLAG_GENERATE_SPIRV_DIRECTLY}).
 */
public final class TargetDesc {
    private TargetDesc() {}

    public static final long SIZE = io.github.refux.slang.ffi.gen.TargetDesc.SIZE;

    /** Allocates one descriptor with the C++ header's default field values. */
    public static MemorySegment allocate(Arena arena) {
        return initDefaults(io.github.refux.slang.ffi.gen.TargetDesc.allocate(arena));
    }

    /** Allocates a contiguous {@code TargetDesc[count]} with defaults in every element. */
    public static MemorySegment allocateArray(Arena arena, int count) {
        MemorySegment array = io.github.refux.slang.ffi.gen.TargetDesc.allocateArray(arena, count);
        for (int i = 0; i < count; i++) {
            initDefaults(element(array, i));
        }
        return array;
    }

    /** Returns the {@code index}-th element of an array allocated by {@link #allocateArray}. */
    public static MemorySegment element(MemorySegment array, int index) {
        return io.github.refux.slang.ffi.gen.TargetDesc.element(array, index);
    }

    private static MemorySegment initDefaults(MemorySegment desc) {
        // structureSize is prefilled by the generated allocate(); remaining defaults are zero.
        io.github.refux.slang.ffi.gen.TargetDesc.setFlags(desc, SlangMiscConstants.kDefaultTargetFlags);
        return desc;
    }

    /** Sets {@code format} to a {@link SlangCompileTarget} value. */
    public static void setFormat(MemorySegment desc, int compileTarget) {
        io.github.refux.slang.ffi.gen.TargetDesc.setFormat(desc, compileTarget);
    }

    /** Sets {@code profile} to an id from {@link IGlobalSession#findProfile(String)}. */
    public static void setProfile(MemorySegment desc, int profileId) {
        io.github.refux.slang.ffi.gen.TargetDesc.setProfile(desc, profileId);
    }

    /** Replaces {@code flags} (default {@code kDefaultTargetFlags}) with an explicit value. */
    public static void setFlags(MemorySegment desc, int flags) {
        io.github.refux.slang.ffi.gen.TargetDesc.setFlags(desc, flags);
    }

    public static void setForceGlslScalarBufferLayout(MemorySegment desc, boolean force) {
        io.github.refux.slang.ffi.gen.TargetDesc.setForceGLSLScalarBufferLayout(desc, force);
    }
}

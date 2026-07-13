package io.github.refux.slang.ffi;

/**
 * Short-name aliases for the {@code SlangCompileTarget} values the tests and early adopters use
 * most; the complete generated enum is {@code ffi.gen.SlangCompileTarget} (all values, straight
 * from slang.h via the API model).
 */
public final class SlangCompileTarget {
    private SlangCompileTarget() {}

    public static final int UNKNOWN = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_TARGET_UNKNOWN;
    public static final int GLSL = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_GLSL;
    public static final int HLSL = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_HLSL;
    public static final int SPIRV = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_SPIRV;
    public static final int SPIRV_ASM = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_SPIRV_ASM;
    public static final int DXIL = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_DXIL;
    public static final int CUDA_SOURCE = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_CUDA_SOURCE;
    public static final int METAL = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_METAL;
    public static final int METAL_LIB = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_METAL_LIB;
    public static final int WGSL = io.github.refux.slang.ffi.gen.SlangCompileTarget.SLANG_WGSL;
}

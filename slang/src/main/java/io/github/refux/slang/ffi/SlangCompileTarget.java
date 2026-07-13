package io.github.refux.slang.ffi;

/**
 * The {@code SlangCompileTarget} values the M1 tests exercise, verified against the v2026.13
 * header by {@code tools/abi-probe.cpp}. Slang's public-header rules make enum values append-only
 * and never reused, so these are stable. The M2 generator emits the complete enum.
 */
public final class SlangCompileTarget {
    private SlangCompileTarget() {}

    public static final int UNKNOWN = 0;
    public static final int GLSL = 2;
    public static final int HLSL = 5;
    public static final int SPIRV = 6;
    public static final int SPIRV_ASM = 7;
    public static final int DXIL = 10;
    public static final int CUDA_SOURCE = 17;
    public static final int METAL = 24;
    public static final int METAL_LIB = 25;
    public static final int WGSL = 28;
}

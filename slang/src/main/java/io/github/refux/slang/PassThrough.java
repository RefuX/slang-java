package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangPassThrough;
import java.util.HashMap;
import java.util.Map;

/**
 * The downstream compilers Slang can hand code to ({@code SlangPassThrough}), as queried by
 * {@link GlobalSession#downstreamCompilerPath} and {@link GlobalSession#isPassThroughSupported}.
 * {@link #value()} / {@link #of(int)} are escape hatches to the raw ABI values; unmapped (newer)
 * values return {@link #UNKNOWN}.
 */
public enum PassThrough {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    NONE(SlangPassThrough.SLANG_PASS_THROUGH_NONE),
    FXC(SlangPassThrough.SLANG_PASS_THROUGH_FXC),
    DXC(SlangPassThrough.SLANG_PASS_THROUGH_DXC),
    /** Backs GLSL-to-SPIR-V; shipped in the natives payload as {@code slang-glslang}. */
    GLSLANG(SlangPassThrough.SLANG_PASS_THROUGH_GLSLANG),
    SPIRV_DIS(SlangPassThrough.SLANG_PASS_THROUGH_SPIRV_DIS),
    CLANG(SlangPassThrough.SLANG_PASS_THROUGH_CLANG),
    VISUAL_STUDIO(SlangPassThrough.SLANG_PASS_THROUGH_VISUAL_STUDIO),
    GCC(SlangPassThrough.SLANG_PASS_THROUGH_GCC),
    GENERIC_C_CPP(SlangPassThrough.SLANG_PASS_THROUGH_GENERIC_C_CPP),
    NVRTC(SlangPassThrough.SLANG_PASS_THROUGH_NVRTC),
    LLVM(SlangPassThrough.SLANG_PASS_THROUGH_LLVM),
    SPIRV_OPT(SlangPassThrough.SLANG_PASS_THROUGH_SPIRV_OPT),
    METAL(SlangPassThrough.SLANG_PASS_THROUGH_METAL),
    TINT(SlangPassThrough.SLANG_PASS_THROUGH_TINT),
    SPIRV_LINK(SlangPassThrough.SLANG_PASS_THROUGH_SPIRV_LINK);

    private static final Map<Integer, PassThrough> BY_VALUE = new HashMap<>();

    static {
        for (PassThrough p : values()) {
            if (p != UNKNOWN) {
                BY_VALUE.put(p.value, p);
            }
        }
    }

    private final int value;

    PassThrough(int value) {
        this.value = value;
    }

    /** The raw {@code SlangPassThrough} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static PassThrough of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

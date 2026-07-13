package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangCompileTarget;
import java.util.HashMap;
import java.util.Map;

/**
 * Code-generation targets ({@code SlangCompileTarget}), with {@link #value()} / {@link #of(int)}
 * escape hatches to the raw ABI values. Values a newer Slang defines but this enum does not know
 * map to {@link #UNKNOWN} — pass raw ints through the {@code ffi} layer to use them.
 */
public enum CompileTarget {
    UNKNOWN(SlangCompileTarget.SLANG_TARGET_UNKNOWN),
    NONE(SlangCompileTarget.SLANG_TARGET_NONE),
    GLSL(SlangCompileTarget.SLANG_GLSL),
    HLSL(SlangCompileTarget.SLANG_HLSL),
    SPIRV(SlangCompileTarget.SLANG_SPIRV),
    SPIRV_ASM(SlangCompileTarget.SLANG_SPIRV_ASM),
    DXBC(SlangCompileTarget.SLANG_DXBC),
    DXBC_ASM(SlangCompileTarget.SLANG_DXBC_ASM),
    DXIL(SlangCompileTarget.SLANG_DXIL),
    DXIL_ASM(SlangCompileTarget.SLANG_DXIL_ASM),
    C_SOURCE(SlangCompileTarget.SLANG_C_SOURCE),
    CPP_SOURCE(SlangCompileTarget.SLANG_CPP_SOURCE),
    HOST_EXECUTABLE(SlangCompileTarget.SLANG_HOST_EXECUTABLE),
    SHADER_SHARED_LIBRARY(SlangCompileTarget.SLANG_SHADER_SHARED_LIBRARY),
    SHADER_HOST_CALLABLE(SlangCompileTarget.SLANG_SHADER_HOST_CALLABLE),
    CUDA_SOURCE(SlangCompileTarget.SLANG_CUDA_SOURCE),
    PTX(SlangCompileTarget.SLANG_PTX),
    CUDA_OBJECT_CODE(SlangCompileTarget.SLANG_CUDA_OBJECT_CODE),
    OBJECT_CODE(SlangCompileTarget.SLANG_OBJECT_CODE),
    HOST_CPP_SOURCE(SlangCompileTarget.SLANG_HOST_CPP_SOURCE),
    HOST_HOST_CALLABLE(SlangCompileTarget.SLANG_HOST_HOST_CALLABLE),
    CPP_PYTORCH_BINDING(SlangCompileTarget.SLANG_CPP_PYTORCH_BINDING),
    METAL(SlangCompileTarget.SLANG_METAL),
    METAL_LIB(SlangCompileTarget.SLANG_METAL_LIB),
    METAL_LIB_ASM(SlangCompileTarget.SLANG_METAL_LIB_ASM),
    HOST_SHARED_LIBRARY(SlangCompileTarget.SLANG_HOST_SHARED_LIBRARY),
    WGSL(SlangCompileTarget.SLANG_WGSL),
    WGSL_SPIRV_ASM(SlangCompileTarget.SLANG_WGSL_SPIRV_ASM),
    WGSL_SPIRV(SlangCompileTarget.SLANG_WGSL_SPIRV),
    HOST_VM(SlangCompileTarget.SLANG_HOST_VM),
    CPP_HEADER(SlangCompileTarget.SLANG_CPP_HEADER),
    CUDA_HEADER(SlangCompileTarget.SLANG_CUDA_HEADER),
    HOST_OBJECT_CODE(SlangCompileTarget.SLANG_HOST_OBJECT_CODE),
    HOST_LLVM_IR(SlangCompileTarget.SLANG_HOST_LLVM_IR),
    SHADER_LLVM_IR(SlangCompileTarget.SLANG_SHADER_LLVM_IR);

    private static final Map<Integer, CompileTarget> BY_VALUE = new HashMap<>();

    static {
        for (CompileTarget t : values()) {
            BY_VALUE.put(t.value, t);
        }
    }

    private final int value;

    CompileTarget(int value) {
        this.value = value;
    }

    /** The raw {@code SlangCompileTarget} ABI value. */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static CompileTarget of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

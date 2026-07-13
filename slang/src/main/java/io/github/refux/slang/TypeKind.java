package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangTypeKind;
import java.util.HashMap;
import java.util.Map;

/**
 * The shape of a reflected type ({@code SlangTypeKind} / {@code TypeReflection::Kind}) with
 * {@link #value()} / {@link #of(int)} escape hatches. Unmapped (newer) values return
 * {@link #UNKNOWN}.
 */
public enum TypeKind {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    NONE(SlangTypeKind.SLANG_TYPE_KIND_NONE),
    STRUCT(SlangTypeKind.SLANG_TYPE_KIND_STRUCT),
    ARRAY(SlangTypeKind.SLANG_TYPE_KIND_ARRAY),
    MATRIX(SlangTypeKind.SLANG_TYPE_KIND_MATRIX),
    VECTOR(SlangTypeKind.SLANG_TYPE_KIND_VECTOR),
    SCALAR(SlangTypeKind.SLANG_TYPE_KIND_SCALAR),
    CONSTANT_BUFFER(SlangTypeKind.SLANG_TYPE_KIND_CONSTANT_BUFFER),
    RESOURCE(SlangTypeKind.SLANG_TYPE_KIND_RESOURCE),
    SAMPLER_STATE(SlangTypeKind.SLANG_TYPE_KIND_SAMPLER_STATE),
    TEXTURE_BUFFER(SlangTypeKind.SLANG_TYPE_KIND_TEXTURE_BUFFER),
    SHADER_STORAGE_BUFFER(SlangTypeKind.SLANG_TYPE_KIND_SHADER_STORAGE_BUFFER),
    PARAMETER_BLOCK(SlangTypeKind.SLANG_TYPE_KIND_PARAMETER_BLOCK),
    GENERIC_TYPE_PARAMETER(SlangTypeKind.SLANG_TYPE_KIND_GENERIC_TYPE_PARAMETER),
    INTERFACE(SlangTypeKind.SLANG_TYPE_KIND_INTERFACE),
    OUTPUT_STREAM(SlangTypeKind.SLANG_TYPE_KIND_OUTPUT_STREAM),
    MESH_OUTPUT(SlangTypeKind.SLANG_TYPE_KIND_MESH_OUTPUT),
    SPECIALIZED(SlangTypeKind.SLANG_TYPE_KIND_SPECIALIZED),
    FEEDBACK(SlangTypeKind.SLANG_TYPE_KIND_FEEDBACK),
    POINTER(SlangTypeKind.SLANG_TYPE_KIND_POINTER),
    DYNAMIC_RESOURCE(SlangTypeKind.SLANG_TYPE_KIND_DYNAMIC_RESOURCE),
    ENUM(SlangTypeKind.SLANG_TYPE_KIND_ENUM);

    private static final Map<Integer, TypeKind> BY_VALUE = new HashMap<>();

    static {
        for (TypeKind k : values()) {
            if (k != UNKNOWN) {
                BY_VALUE.put(k.value, k);
            }
        }
    }

    private final int value;

    TypeKind(int value) {
        this.value = value;
    }

    /** The raw {@code SlangTypeKind} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static TypeKind of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

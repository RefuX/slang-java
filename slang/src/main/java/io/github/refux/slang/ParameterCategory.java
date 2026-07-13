package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangParameterCategory;
import java.util.HashMap;
import java.util.Map;

/**
 * What kind of resource/register a shader parameter consumes ({@code SlangParameterCategory}),
 * with {@link #value()} / {@link #of(int)} escape hatches. Unmapped (newer) values return
 * {@link #UNKNOWN}.
 */
public enum ParameterCategory {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    NONE(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_NONE),
    MIXED(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_MIXED),
    CONSTANT_BUFFER(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_CONSTANT_BUFFER),
    SHADER_RESOURCE(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_SHADER_RESOURCE),
    UNORDERED_ACCESS(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_UNORDERED_ACCESS),
    VARYING_INPUT(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_VARYING_INPUT),
    VARYING_OUTPUT(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_VARYING_OUTPUT),
    SAMPLER_STATE(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_SAMPLER_STATE),
    UNIFORM(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_UNIFORM),
    DESCRIPTOR_TABLE_SLOT(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_DESCRIPTOR_TABLE_SLOT),
    SPECIALIZATION_CONSTANT(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_SPECIALIZATION_CONSTANT),
    PUSH_CONSTANT_BUFFER(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_PUSH_CONSTANT_BUFFER),
    REGISTER_SPACE(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_REGISTER_SPACE),
    GENERIC(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_GENERIC),
    RAY_PAYLOAD(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_RAY_PAYLOAD),
    HIT_ATTRIBUTES(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_HIT_ATTRIBUTES),
    CALLABLE_PAYLOAD(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_CALLABLE_PAYLOAD),
    SHADER_RECORD(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_SHADER_RECORD),
    EXISTENTIAL_TYPE_PARAM(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_EXISTENTIAL_TYPE_PARAM),
    EXISTENTIAL_OBJECT_PARAM(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_EXISTENTIAL_OBJECT_PARAM),
    SUB_ELEMENT_REGISTER_SPACE(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_SUB_ELEMENT_REGISTER_SPACE),
    SUBPASS(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_SUBPASS),
    METAL_ARGUMENT_BUFFER_ELEMENT(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_METAL_ARGUMENT_BUFFER_ELEMENT),
    METAL_ATTRIBUTE(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_METAL_ATTRIBUTE),
    METAL_PAYLOAD(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_METAL_PAYLOAD);

    private static final Map<Integer, ParameterCategory> BY_VALUE = new HashMap<>();

    static {
        for (ParameterCategory c : values()) {
            if (c != UNKNOWN) {
                BY_VALUE.put(c.value, c);
            }
        }
    }

    private final int value;

    ParameterCategory(int value) {
        this.value = value;
    }

    /** The raw {@code SlangParameterCategory} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static ParameterCategory of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

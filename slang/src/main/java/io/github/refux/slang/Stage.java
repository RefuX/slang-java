package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangStage;
import java.util.HashMap;
import java.util.Map;

/**
 * Pipeline stages ({@code SlangStage}) with {@link #value()} / {@link #of(int)} escape hatches.
 * Unmapped (newer) values return {@link #UNKNOWN}. {@code SLANG_STAGE_PIXEL} is an alias of
 * {@link #FRAGMENT} in the native enum and maps accordingly.
 */
public enum Stage {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    NONE(SlangStage.SLANG_STAGE_NONE),
    VERTEX(SlangStage.SLANG_STAGE_VERTEX),
    HULL(SlangStage.SLANG_STAGE_HULL),
    DOMAIN(SlangStage.SLANG_STAGE_DOMAIN),
    GEOMETRY(SlangStage.SLANG_STAGE_GEOMETRY),
    FRAGMENT(SlangStage.SLANG_STAGE_FRAGMENT),
    COMPUTE(SlangStage.SLANG_STAGE_COMPUTE),
    RAY_GENERATION(SlangStage.SLANG_STAGE_RAY_GENERATION),
    INTERSECTION(SlangStage.SLANG_STAGE_INTERSECTION),
    ANY_HIT(SlangStage.SLANG_STAGE_ANY_HIT),
    CLOSEST_HIT(SlangStage.SLANG_STAGE_CLOSEST_HIT),
    MISS(SlangStage.SLANG_STAGE_MISS),
    CALLABLE(SlangStage.SLANG_STAGE_CALLABLE),
    MESH(SlangStage.SLANG_STAGE_MESH),
    AMPLIFICATION(SlangStage.SLANG_STAGE_AMPLIFICATION),
    DISPATCH(SlangStage.SLANG_STAGE_DISPATCH),
    NODE(SlangStage.SLANG_STAGE_NODE);

    private static final Map<Integer, Stage> BY_VALUE = new HashMap<>();

    static {
        for (Stage s : values()) {
            if (s != UNKNOWN) {
                BY_VALUE.put(s.value, s);
            }
        }
    }

    private final int value;

    Stage(int value) {
        this.value = value;
    }

    /** The raw {@code SlangStage} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static Stage of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangOptimizationLevel;
import java.util.HashMap;
import java.util.Map;

/**
 * How hard Slang optimizes generated code ({@code SlangOptimizationLevel}), set with
 * {@link SessionBuilder#optimization}. {@link #value()} / {@link #of(int)} are escape hatches to the
 * raw ABI values; unmapped (newer) values return {@link #UNKNOWN}.
 */
public enum OptimizationLevel {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    NONE(SlangOptimizationLevel.SLANG_OPTIMIZATION_LEVEL_NONE),
    /** Slang's default: balances code quality against compile time. */
    DEFAULT(SlangOptimizationLevel.SLANG_OPTIMIZATION_LEVEL_DEFAULT),
    HIGH(SlangOptimizationLevel.SLANG_OPTIMIZATION_LEVEL_HIGH),
    /** Includes optimizations that may take a very long time or be unsafe. */
    MAXIMAL(SlangOptimizationLevel.SLANG_OPTIMIZATION_LEVEL_MAXIMAL);

    private static final Map<Integer, OptimizationLevel> BY_VALUE = new HashMap<>();

    static {
        for (OptimizationLevel level : values()) {
            if (level != UNKNOWN) {
                BY_VALUE.put(level.value, level);
            }
        }
    }

    private final int value;

    OptimizationLevel(int value) {
        this.value = value;
    }

    /** The raw {@code SlangOptimizationLevel} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static OptimizationLevel of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

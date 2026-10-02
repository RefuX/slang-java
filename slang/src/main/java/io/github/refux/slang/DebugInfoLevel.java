package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangDebugInfoLevel;
import java.util.HashMap;
import java.util.Map;

/**
 * How much debug information Slang emits ({@code SlangDebugInfoLevel}), set with
 * {@link SessionBuilder#debugInfo}. {@link #value()} / {@link #of(int)} are escape hatches to the
 * raw ABI values; unmapped (newer) values return {@link #UNKNOWN}.
 */
public enum DebugInfoLevel {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    /** No debug information — Slang's default. */
    NONE(SlangDebugInfoLevel.SLANG_DEBUG_INFO_LEVEL_NONE),
    MINIMAL(SlangDebugInfoLevel.SLANG_DEBUG_INFO_LEVEL_MINIMAL),
    STANDARD(SlangDebugInfoLevel.SLANG_DEBUG_INFO_LEVEL_STANDARD),
    MAXIMAL(SlangDebugInfoLevel.SLANG_DEBUG_INFO_LEVEL_MAXIMAL);

    private static final Map<Integer, DebugInfoLevel> BY_VALUE = new HashMap<>();

    static {
        for (DebugInfoLevel level : values()) {
            if (level != UNKNOWN) {
                BY_VALUE.put(level.value, level);
            }
        }
    }

    private final int value;

    DebugInfoLevel(int value) {
        this.value = value;
    }

    /** The raw {@code SlangDebugInfoLevel} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static DebugInfoLevel of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

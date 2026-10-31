package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangDiagnosticFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * How human-readable diagnostics are presented ({@code SlangDiagnosticFormat}), set with
 * {@link SessionBuilder#diagnosticFormat}. {@link #value()} / {@link #of(int)} are escape hatches to
 * the raw ABI values; unmapped (newer) values return {@link #UNKNOWN}.
 */
public enum DiagnosticFormat {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    /** Slang's own presentation. */
    DEFAULT(SlangDiagnosticFormat.SLANG_DIAGNOSTIC_FORMAT_DEFAULT),
    /** Visual Studio style, {@code file(line,col): error ...}, which IDEs can parse. */
    VISUAL_STUDIO(SlangDiagnosticFormat.SLANG_DIAGNOSTIC_FORMAT_VISUAL_STUDIO);

    private static final Map<Integer, DiagnosticFormat> BY_VALUE = new HashMap<>();

    static {
        for (DiagnosticFormat format : values()) {
            if (format != UNKNOWN) {
                BY_VALUE.put(format.value, format);
            }
        }
    }

    private final int value;

    DiagnosticFormat(int value) {
        this.value = value;
    }

    /** The raw {@code SlangDiagnosticFormat} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static DiagnosticFormat of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}

package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangMatrixLayoutMode;
import java.util.HashMap;
import java.util.Map;

/**
 * How a session lays out matrix types ({@code SlangMatrixLayoutMode}), with {@link #value()} /
 * {@link #of(int)} escape hatches to the raw ABI values.
 *
 * <p><b>Slang's own two defaults disagree, so a project that compiles the same shader both ways
 * must say which it wants.</b> {@code SessionDesc} in the C++ header defaults to
 * {@link #ROW_MAJOR}, and {@link SessionBuilder} follows the header. The {@code slangc} command
 * line defaults to column-major instead. Compile a shader offline with {@code slangc} and again at
 * run time through this library, and the two SPIR-V modules carry opposite {@code RowMajor} /
 * {@code ColMajor} member decorations for the same source — every matrix is silently transposed
 * relative to the other build. Nothing diagnoses it: both modules are valid, both load, and the
 * only symptom is wrong geometry.
 *
 * <p>Note the naming inverts on the way to SPIR-V: {@link #COLUMN_MAJOR} here emits {@code RowMajor}
 * member decorations, matching what {@code slangc} produces by default.
 */
public enum MatrixLayout {
    /** Slang's mode is unset; the compiler picks. */
    UNKNOWN(SlangMatrixLayoutMode.SLANG_MATRIX_LAYOUT_MODE_UNKNOWN),
    /** The {@code SessionDesc} header default. Emits {@code ColMajor} in SPIR-V. */
    ROW_MAJOR(SlangMatrixLayoutMode.SLANG_MATRIX_LAYOUT_ROW_MAJOR),
    /** What the {@code slangc} command line uses by default. Emits {@code RowMajor} in SPIR-V. */
    COLUMN_MAJOR(SlangMatrixLayoutMode.SLANG_MATRIX_LAYOUT_COLUMN_MAJOR);

    private static final Map<Integer, MatrixLayout> BY_VALUE = new HashMap<>();

    static {
        for (MatrixLayout layout : values()) {
            BY_VALUE.put(layout.value, layout);
        }
    }

    private final int value;

    MatrixLayout(int value) {
        this.value = value;
    }

    /**
     * Maps a raw {@code SlangMatrixLayoutMode} to this enum.
     *
     * @param value the raw ABI value.
     * @return the matching constant, or {@link #UNKNOWN} for a value this enum does not know.
     */
    public static MatrixLayout of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }

    /**
     * This layout's raw {@code SlangMatrixLayoutMode} value.
     *
     * @return the ABI value.
     */
    public int value() {
        return value;
    }
}

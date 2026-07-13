package io.github.refux.slang.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Convenience over the generated {@code ffi.gen.PreprocessorMacroDesc} — a
 * {@code {const char* name; const char* value;}} pair (16 bytes).
 */
public final class PreprocessorMacroDesc {
    private PreprocessorMacroDesc() {}

    /** Allocates a zeroed {@code PreprocessorMacroDesc[count]}. */
    public static MemorySegment allocateArray(Arena arena, int count) {
        return io.github.refux.slang.ffi.gen.PreprocessorMacroDesc.allocateArray(arena, count);
    }

    /** Sets element {@code index} to point at NUL-terminated UTF-8 {@code name}/{@code value}. */
    public static void set(MemorySegment array, int index, MemorySegment name, MemorySegment value) {
        MemorySegment element = io.github.refux.slang.ffi.gen.PreprocessorMacroDesc.element(array, index);
        io.github.refux.slang.ffi.gen.PreprocessorMacroDesc.setName(element, name);
        io.github.refux.slang.ffi.gen.PreprocessorMacroDesc.setValue(element, value);
    }
}

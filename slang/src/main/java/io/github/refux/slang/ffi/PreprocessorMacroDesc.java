package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;

/**
 * Memory layout for {@code struct slang::PreprocessorMacroDesc} — a {@code {const char* name;
 * const char* value;}} pair (16 bytes, verified by {@code tools/abi-probe.cpp}).
 */
public final class PreprocessorMacroDesc {
    private PreprocessorMacroDesc() {}

    public static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
                    ADDRESS.withName("name"), ADDRESS.withName("value"))
            .withName("PreprocessorMacroDesc");

    /** Allocates a zeroed {@code PreprocessorMacroDesc[count]}. */
    public static MemorySegment allocateArray(Arena arena, int count) {
        return arena.allocate(LAYOUT.byteSize() * count, LAYOUT.byteAlignment());
    }

    /** Sets element {@code index} to point at NUL-terminated UTF-8 {@code name}/{@code value}. */
    public static void set(MemorySegment array, int index, MemorySegment name, MemorySegment value) {
        MemorySegment element = array.asSlice(LAYOUT.byteSize() * index, LAYOUT.byteSize());
        element.set(ADDRESS, 0, name);
        element.set(ADDRESS, ADDRESS.byteSize(), value);
    }
}

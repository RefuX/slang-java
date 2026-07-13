package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Small marshaling helpers for the descriptor structs (arrays of C strings and pointers). */
public final class Marshal {
    private Marshal() {}

    /**
     * Allocates a {@code char*[]} whose elements point at NUL-terminated UTF-8 copies of
     * {@code strings}, all owned by {@code arena} — the shape of {@code SessionDesc.searchPaths}.
     */
    public static MemorySegment utf8PointerArray(Arena arena, String... strings) {
        MemorySegment array = arena.allocate(ADDRESS, strings.length);
        for (int i = 0; i < strings.length; i++) {
            array.setAtIndex(ADDRESS, i, arena.allocateFrom(strings[i]));
        }
        return array;
    }
}

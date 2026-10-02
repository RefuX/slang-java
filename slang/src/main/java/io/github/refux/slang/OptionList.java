package io.github.refux.slang;

import io.github.refux.slang.ffi.CompilerOptionEntry;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiler option entries collected by {@link SessionBuilder} and {@link TargetOptions}, kept in
 * call order: Slang treats some options (capabilities, warning groups) as repeatable.
 */
final class OptionList {
    private record Entry(int name, int int0, int int1, String string) {}

    private final List<Entry> entries = new ArrayList<>();

    void add(int name, int value0, int value1) {
        entries.add(new Entry(name, value0, value1, null));
    }

    void add(int name, String value) {
        entries.add(new Entry(name, 0, 0, Objects.requireNonNull(value, "value")));
    }

    int size() {
        return entries.size();
    }

    /** Writes the entries as a native {@code CompilerOptionEntry[]} whose strings live in {@code arena}. */
    MemorySegment write(Arena arena) {
        MemorySegment array = CompilerOptionEntry.allocateArray(arena, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.string() == null) {
                CompilerOptionEntry.setInt(array, i, entry.name(), entry.int0(), entry.int1());
            } else {
                CompilerOptionEntry.setString(
                        array, i, entry.name(), arena.allocateFrom(entry.string()), MemorySegment.NULL);
            }
        }
        return array;
    }
}

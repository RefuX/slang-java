package io.github.refux.slang.ffi;

import io.github.refux.slang.ffi.gen.CompilerOptionValue;
import io.github.refux.slang.ffi.gen.CompilerOptionValueKind;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Convenience over the generated {@code ffi.gen.CompilerOptionEntry} layout (offsets
 * clang-verified, size 40): a {@code CompilerOptionName} plus a {@code CompilerOptionValue} holding
 * either two ints or two strings. {@link SessionDesc} and {@link TargetDesc} each point at an array
 * of these, which Slang copies during {@code createSession} like the rest of the descriptor.
 */
public final class CompilerOptionEntry {
    private CompilerOptionEntry() {}

    /** Allocates a zeroed {@code CompilerOptionEntry[count]}. */
    public static MemorySegment allocateArray(Arena arena, int count) {
        return io.github.refux.slang.ffi.gen.CompilerOptionEntry.allocateArray(arena, count);
    }

    /** Sets element {@code index} to an int-valued option; bool options take 0 or 1. */
    public static void setInt(MemorySegment array, int index, int name, int value0, int value1) {
        MemorySegment value = start(array, index, name, CompilerOptionValueKind.Int);
        CompilerOptionValue.setIntValue0(value, value0);
        CompilerOptionValue.setIntValue1(value, value1);
    }

    /** Sets element {@code index} to a string-valued option; {@code value1} may be {@code NULL}. */
    public static void setString(MemorySegment array, int index, int name, MemorySegment value0, MemorySegment value1) {
        MemorySegment value = start(array, index, name, CompilerOptionValueKind.String);
        CompilerOptionValue.setStringValue0(value, value0);
        CompilerOptionValue.setStringValue1(value, value1);
    }

    private static MemorySegment start(MemorySegment array, int index, int name, int kind) {
        MemorySegment entry = io.github.refux.slang.ffi.gen.CompilerOptionEntry.element(array, index);
        io.github.refux.slang.ffi.gen.CompilerOptionEntry.setName(entry, name);
        MemorySegment value = io.github.refux.slang.ffi.gen.CompilerOptionEntry.value(entry);
        CompilerOptionValue.setKind(value, kind);
        return value;
    }
}

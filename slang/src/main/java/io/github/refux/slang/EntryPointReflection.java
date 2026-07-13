package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangReflectionAPI;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.AbstractList;
import java.util.List;

/**
 * Reflected {@code slang::EntryPointReflection}: name, stage, parameters, and (for compute)
 * the thread-group size.
 */
public final class EntryPointReflection extends EntryPointReflectionGen {
    EntryPointReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }

    /** The entry point's name (alias of {@code getName()}). */
    public String name() {
        return getName();
    }

    /** The pipeline stage as an idiomatic enum ({@code getStage()} returns the raw value). */
    public Stage stage() {
        return Stage.of(getStage());
    }

    /** The {@code [numthreads(x, y, z)]} sizes of a compute entry point, as {x, y, z}. */
    public long[] computeThreadGroupSize() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG, 3);
            SlangReflectionAPI.spReflectionEntryPoint_getComputeThreadGroupSize(segment(), 3, out);
            return out.toArray(ValueLayout.JAVA_LONG);
        }
    }

    /** The entry point's parameters as a lazy list view. */
    public List<VariableLayoutReflection> parameters() {
        int count = getParameterCount();
        return new AbstractList<>() {
            @Override
            public VariableLayoutReflection get(int index) {
                return getParameterByIndex(index);
            }

            @Override
            public int size() {
                return count;
            }
        };
    }
}

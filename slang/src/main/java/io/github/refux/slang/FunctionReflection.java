package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::FunctionReflection}. The generated base ({@code io.github.refux.slang.gen.FunctionReflection})
 * provides the full method surface; idiomatic conveniences land here as they are needed.
 */
public final class FunctionReflection extends io.github.refux.slang.gen.FunctionReflection {
    /** Wraps a raw reflection pointer; normally obtained by traversing the reflection tree. */
    public FunctionReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

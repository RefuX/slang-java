package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::GenericReflection}. The generated base ({@code io.github.refux.slang.gen.GenericReflection})
 * provides the full method surface; idiomatic conveniences land here as they are needed.
 */
public final class GenericReflection extends io.github.refux.slang.gen.GenericReflection {
    /** Wraps a raw reflection pointer; normally obtained by traversing the reflection tree. */
    public GenericReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::TypeParameterReflection}. The generated base ({@code io.github.refux.slang.gen.TypeParameterReflection})
 * provides the full method surface; idiomatic conveniences land here as they are needed.
 */
public final class TypeParameterReflection extends io.github.refux.slang.gen.TypeParameterReflection {
    /** Wraps a raw reflection pointer; normally obtained by traversing the reflection tree. */
    public TypeParameterReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

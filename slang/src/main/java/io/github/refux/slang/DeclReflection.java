package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::DeclReflection}. The generated base ({@code io.github.refux.slang.gen.DeclReflection})
 * provides the full method surface; idiomatic conveniences land here as they are needed.
 */
public final class DeclReflection extends io.github.refux.slang.gen.DeclReflection {
    /** Wraps a raw reflection pointer; normally obtained by traversing the reflection tree. */
    public DeclReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

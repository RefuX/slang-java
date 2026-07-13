package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::Attribute}. The generated base ({@code io.github.refux.slang.gen.Attribute})
 * provides the full method surface; idiomatic conveniences land here as they are needed.
 */
public final class Attribute extends io.github.refux.slang.gen.Attribute {
    /** Wraps a raw reflection pointer; normally obtained by traversing the reflection tree. */
    public Attribute(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

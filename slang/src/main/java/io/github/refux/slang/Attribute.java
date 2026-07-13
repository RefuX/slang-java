package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::Attribute}. The generated base ({@code AttributeGen}) provides the full
 * method surface; idiomatic conveniences land here as they are needed.
 */
public final class Attribute extends AttributeGen {
    Attribute(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

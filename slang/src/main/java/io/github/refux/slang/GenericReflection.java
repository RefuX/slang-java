package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::GenericReflection}. The generated base ({@code GenericReflectionGen}) provides the full
 * method surface; idiomatic conveniences land here as they are needed.
 */
public final class GenericReflection extends GenericReflectionGen {
    GenericReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

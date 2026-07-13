package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::DeclReflection}. The generated base ({@code DeclReflectionGen}) provides the full
 * method surface; idiomatic conveniences land here as they are needed.
 */
public final class DeclReflection extends DeclReflectionGen {
    DeclReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::TypeParameterReflection}. The generated base ({@code TypeParameterReflectionGen}) provides the full
 * method surface; idiomatic conveniences land here as they are needed.
 */
public final class TypeParameterReflection extends TypeParameterReflectionGen {
    TypeParameterReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

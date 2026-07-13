package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::FunctionReflection}. The generated base ({@code FunctionReflectionGen}) provides the full
 * method surface; idiomatic conveniences land here as they are needed.
 */
public final class FunctionReflection extends FunctionReflectionGen {
    FunctionReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }
}

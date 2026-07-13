package io.github.refux.slang;

import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::VariableReflection}: a declaration — name, type, user attributes —
 * independent of any layout.
 */
public final class VariableReflection extends VariableReflectionGen {
    VariableReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }

    /** The variable's name (alias of {@code getName()}). */
    public String name() {
        return getName();
    }

    /** The variable's type (alias of {@code getType()}). */
    public TypeReflection type() {
        return getType();
    }
}

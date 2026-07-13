package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangParameterCategory;
import java.lang.foreign.MemorySegment;

/**
 * Reflected {@code slang::VariableLayoutReflection}: a variable <em>as laid out</em> — its
 * offset/binding within the enclosing scope, plus links back to the declaration
 * ({@link #variable()}) and the laid-out type ({@link #typeLayout()}).
 */
public final class VariableLayoutReflection extends io.github.refux.slang.gen.VariableLayoutReflection {
    public VariableLayoutReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }

    /** The variable's name, read through {@link #getVariable()}, like C++ {@code getName()}. */
    public String name() {
        VariableReflection variable = getVariable();
        return variable == null ? null : variable.getName();
    }

    /** The declaration this layout applies to (alias of {@code getVariable()}). */
    public VariableReflection variable() {
        return getVariable();
    }

    /** The laid-out type (alias of {@code getTypeLayout()}). */
    public TypeLayoutReflection typeLayout() {
        return getTypeLayout();
    }

    /**
     * What this parameter consumes, as an idiomatic enum — the type layout's parameter category,
     * like C++ {@code getCategory()}.
     */
    public ParameterCategory category() {
        return ParameterCategory.of(getTypeLayout().getParameterCategory());
    }

    /** Byte offset within the enclosing scope (the {@code uniform} layout unit). */
    public long offset() {
        return getOffset(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_UNIFORM);
    }

    /** Offset in the given layout unit, e.g. register index for resource categories. */
    public long offset(ParameterCategory category) {
        return getOffset(category.value());
    }
}

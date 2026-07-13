package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.SlangParameterCategory;
import java.lang.foreign.MemorySegment;
import java.util.AbstractList;
import java.util.List;

/**
 * Reflected {@code slang::TypeLayoutReflection}: a type <em>as laid out</em> for one target —
 * sizes, strides, alignment, field offsets, binding ranges. Per the user guide, size/stride/
 * alignment default to bytes (the {@code uniform} layout unit) unless a category is given.
 */
public final class TypeLayoutReflection extends io.github.refux.slang.gen.TypeLayoutReflection {
    public TypeLayoutReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }

    /** The layout's kind as an idiomatic enum ({@code getKind()} returns the raw value). */
    public TypeKind kind() {
        return TypeKind.of(getKind());
    }

    /** The underlying type's name. */
    public String name() {
        TypeReflection type = getType();
        return type == null ? null : type.getName();
    }

    /** Size in bytes (the {@code uniform} layout unit), like C++ {@code getSize()}'s default. */
    public long size() {
        return getSize(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_UNIFORM);
    }

    /** Array stride in bytes, like C++ {@code getStride()}'s default. */
    public long stride() {
        return getStride(SlangParameterCategory.SLANG_PARAMETER_CATEGORY_UNIFORM);
    }

    /** The fields as a lazy list view over {@code getFieldCount/getFieldByIndex}. */
    public List<VariableLayoutReflection> fields() {
        int count = getFieldCount();
        return new AbstractList<>() {
            @Override
            public VariableLayoutReflection get(int index) {
                return getFieldByIndex(index);
            }

            @Override
            public int size() {
                return count;
            }
        };
    }
}

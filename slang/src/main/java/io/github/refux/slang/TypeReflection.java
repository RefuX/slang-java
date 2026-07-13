package io.github.refux.slang;

import java.lang.foreign.MemorySegment;
import java.util.AbstractList;
import java.util.List;

/**
 * Reflected {@code slang::TypeReflection}: the language-level shape of a type — kind, name,
 * struct fields, element types of arrays/vectors, matrix dimensions. Layout questions (sizes,
 * offsets, registers) belong to {@link TypeLayoutReflection}.
 */
public final class TypeReflection extends TypeReflectionGen {
    TypeReflection(MemorySegment self, Object owner) {
        super(self, owner);
    }

    /** The type's kind as an idiomatic enum ({@code getKind()} returns the raw value). */
    public TypeKind kind() {
        return TypeKind.of(getKind());
    }

    /** The type's name (alias of {@code getName()}). */
    public String name() {
        return getName();
    }

    /**
     * Element count of an array or vector type — like C++ {@code getElementCount()}, whose
     * specialization argument defaults to null.
     */
    public long elementCount() {
        return getElementCount(null);
    }

    /** The struct fields as a lazy list view over {@code getFieldCount/getFieldByIndex}. */
    public List<VariableReflection> fields() {
        int count = getFieldCount();
        return new AbstractList<>() {
            @Override
            public VariableReflection get(int index) {
                return getFieldByIndex(index);
            }

            @Override
            public int size() {
                return count;
            }
        };
    }
}

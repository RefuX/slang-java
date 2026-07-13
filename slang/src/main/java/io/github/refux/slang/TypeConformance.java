package io.github.refux.slang;

import io.github.refux.slang.ffi.ITypeConformance;

/**
 * A type-conformance component ({@code slang::ITypeConformance}) from
 * {@link Session#createTypeConformance}. It is a {@link ComponentType}, so it composites and links
 * with a program like any other component; doing so makes the concrete type's witness table
 * available for dynamic dispatch of buffer-sourced interface values.
 */
public final class TypeConformance extends ComponentType {

    TypeConformance(Session session, ITypeConformance conformance) {
        super(session, conformance);
    }
}

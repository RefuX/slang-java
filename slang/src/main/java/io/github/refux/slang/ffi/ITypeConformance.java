package io.github.refux.slang.ffi;

import java.lang.foreign.MemorySegment;

/**
 * Wrapper for {@code slang::ITypeConformance} — a caller-owned component recording that a concrete
 * type implements an interface. It is an {@link IComponentType}, so it composites and links like
 * any other component (e.g. {@code session.composite(module, entryPoint, conformance)}). Raw
 * vtable dispatch lives in the generated {@code ffi.gen.ITypeConformance}.
 */
public final class ITypeConformance extends IComponentType {
    ITypeConformance(MemorySegment pointer) {
        super(pointer); // caller-owned (an out-param from createTypeConformanceComponentType)
    }
}

package io.github.refux.slang.ffi;

import java.lang.foreign.MemorySegment;

/**
 * M1 micro-binding of {@code slang::IEntryPoint} — inherits everything the pipeline needs from
 * {@link IComponentType}; its one own method (function reflection) arrives with M4.
 */
public final class IEntryPoint extends IComponentType {
    IEntryPoint(MemorySegment pointer) {
        super(pointer);
    }
}

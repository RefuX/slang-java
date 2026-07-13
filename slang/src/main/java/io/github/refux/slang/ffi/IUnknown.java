package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.ffi.gen.ISlangUnknown;
import java.lang.foreign.MemorySegment;

/**
 * Base wrapper for Slang's COM-style objects ({@code ISlangUnknown}). Dispatch itself lives in
 * the generated layer ({@code ffi.gen}, static methods taking {@code self}); this class layers
 * the ownership model on top: a wrapper holds at most one native reference, released exactly
 * once by {@link #close()}.
 */
public class IUnknown implements AutoCloseable {
    private final MemorySegment self;
    private final boolean owned;
    private boolean closed;

    protected IUnknown(MemorySegment pointer) {
        this(pointer, true);
    }

    /**
     * @param owned whether this wrapper holds a native reference of its own, to be released by
     *     {@link #close()}. COM out-params hand the caller a reference (owned = true), but some
     *     Slang APIs return borrowed pointers — e.g. modules from {@code ISession.loadModule*}
     *     are owned by their session and must not be released by the caller.
     */
    protected IUnknown(MemorySegment pointer, boolean owned) {
        if (pointer == null || pointer.address() == 0) {
            throw new IllegalArgumentException("null COM object pointer");
        }
        // Pointers read from native memory arrive as zero-length segments; widen exactly enough
        // to read the vtable pointer field at offset 0.
        this.self = pointer.reinterpret(ADDRESS.byteSize());
        this.owned = owned;
    }

    /** The raw COM object pointer, for passing into the generated layer or other native calls. */
    public final MemorySegment segment() {
        return self;
    }

    /** COM {@code AddRef}; returns the new reference count. */
    public final long addRef() {
        return Integer.toUnsignedLong(ISlangUnknown.addRef(self));
    }

    /**
     * COM {@code Release}; returns the remaining reference count. Prefer {@link #close()}, which
     * releases the wrapper's own reference exactly once — call this directly only to balance an
     * explicit {@link #addRef()}.
     */
    public final long release() {
        return Integer.toUnsignedLong(ISlangUnknown.release(self));
    }

    /** Releases the wrapper's reference (a no-op for borrowed wrappers). Idempotent. */
    @Override
    public final void close() {
        if (!closed) {
            closed = true;
            if (owned) {
                release();
            }
        }
    }
}

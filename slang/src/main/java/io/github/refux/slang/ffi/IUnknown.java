package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Base wrapper for Slang's COM-style objects ({@code ISlangUnknown}): a native pointer whose
 * first field is the vtable pointer, with slots 0–2 being
 * {@code queryInterface} / {@code addRef} / {@code release}.
 *
 * <p>Dispatch works exactly as described in DESIGN.md §5: read the vtable pointer from the
 * object, index the slot, and invoke through an <em>unbound</em> downcall handle (one per
 * function shape, shared across methods) whose leading argument is the function address.
 *
 * <p>The wrapper owns exactly one native reference; {@link #close()} releases it once.
 */
public class IUnknown implements AutoCloseable {
    public static final int VT_QUERY_INTERFACE = 0;
    public static final int VT_ADD_REF = 1;
    public static final int VT_RELEASE = 2;

    /** {@code uint32_t (*)(void* self)} — shape of both {@code addRef} and {@code release}. */
    private static final MethodHandle MH_UINT_OF_SELF =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS));

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

    /** The raw COM object pointer, e.g. for passing back into other native calls. */
    public final MemorySegment segment() {
        return self;
    }

    /** Reads the function pointer stored in vtable slot {@code slot} of this object. */
    protected final MemorySegment fnPtr(int slot) {
        MemorySegment vtable = self.get(ADDRESS, 0).reinterpret((slot + 1) * ADDRESS.byteSize());
        return vtable.getAtIndex(ADDRESS, slot);
    }

    /** COM {@code AddRef}; returns the new reference count. */
    public final long addRef() {
        try {
            return Integer.toUnsignedLong((int) MH_UINT_OF_SELF.invokeExact(fnPtr(VT_ADD_REF), self));
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }

    /**
     * COM {@code Release}; returns the remaining reference count. Prefer {@link #close()}, which
     * releases the wrapper's own reference exactly once — call this directly only to balance an
     * explicit {@link #addRef()}.
     */
    public final long release() {
        try {
            return Integer.toUnsignedLong((int) MH_UINT_OF_SELF.invokeExact(fnPtr(VT_RELEASE), self));
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
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

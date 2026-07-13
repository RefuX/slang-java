package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.github.refux.slang.ffi.gen.FfiSupport;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime for implementing Slang's COM interfaces <em>in Java</em> (DESIGN.md §6).
 *
 * <p>Each object is a 16-byte native allocation {@code [vtable* | instanceId]} in its own
 * <em>shared</em> arena (upcalls may arrive on any native thread). Vtable slots are upcall stubs
 * created once per interface class; the stubs resolve the Java object through a registry keyed
 * by the instance id. The registry holds objects strongly while their native reference count is
 * positive, so the GC can never free an object native code still uses.
 *
 * <p>Reference counting is Java-side: the count starts at 1 (the creation reference); when it
 * reaches zero the registry entry is removed and the native allocation freed. COM contract
 * notes: {@code queryInterface} answers for the interface chain's IIDs plus
 * {@code ISlangUnknown} and add-refs on success; callers must hold a reference across calls.
 *
 * <p>Every stub body catches {@link Throwable} and returns an error result instead — an
 * exception escaping an FFM upcall would terminate the VM.
 */
public abstract class JavaComObject {
    static final ConcurrentHashMap<Long, JavaComObject> REGISTRY = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong();

    /** {@code SLANG_E_NO_INTERFACE} — queryInterface refusal. */
    public static final int SLANG_E_NO_INTERFACE = 0x80004002;

    /** {@code SLANG_E_NOT_FOUND} ({@code SLANG_MAKE_ERROR(SLANG_FACILITY_CORE, 5)}). */
    public static final int SLANG_E_NOT_FOUND = 0x82000005;

    /** Shared IUnknown stubs — slots 0..2 of every vtable built by this runtime. */
    static final MemorySegment QUERY_INTERFACE_STUB = upcall(
            JavaComObject.class, "queryInterfaceStub", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    static final MemorySegment ADD_REF_STUB =
            upcall(JavaComObject.class, "addRefStub", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    static final MemorySegment RELEASE_STUB =
            upcall(JavaComObject.class, "releaseStub", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Owns the object's native memory: the 16-byte header plus subclass payloads. */
    protected final Arena arena = Arena.ofShared();

    private final long id;
    private final MemorySegment self;
    private final AtomicInteger refCount = new AtomicInteger(1);

    protected JavaComObject(MemorySegment vtable) {
        this.id = NEXT_ID.incrementAndGet();
        this.self = arena.allocate(16, ADDRESS.byteSize());
        self.set(ADDRESS, 0, vtable);
        self.set(JAVA_LONG, 8, id);
        REGISTRY.put(id, this);
    }

    /** The native COM pointer; pass it wherever slang.h expects the implemented interface. */
    public final MemorySegment segment() {
        return self;
    }

    /**
     * IIDs this object answers {@code queryInterface} for — the interface chain plus
     * {@code ISlangUnknown} — each as the two little-endian longs of the SlangUUID memory image
     * (see {@link #iid(String)}).
     */
    protected abstract long[][] supportedIids();

    /** Adds one native reference; returns the new count. */
    public final long addRef() {
        return Integer.toUnsignedLong(refCount.incrementAndGet());
    }

    /**
     * Drops one native reference; at zero the object is unregistered and its native memory
     * freed. The creator's own reference must be released once ownership has been handed to
     * native code (e.g. after {@code createSession} retained a file system).
     */
    public final long release() {
        int remaining = refCount.decrementAndGet();
        if (remaining == 0) {
            REGISTRY.remove(id);
            arena.close();
        }
        return Integer.toUnsignedLong(remaining);
    }

    /** Objects currently alive (native references or unreleased creation references). */
    public static int liveCount() {
        return REGISTRY.size();
    }

    // ------------------------------------------------------------- shared stub implementations

    static JavaComObject resolve(MemorySegment self) {
        return REGISTRY.get(self.reinterpret(16).get(JAVA_LONG, 8));
    }

    static int queryInterfaceStub(MemorySegment self, MemorySegment uuid, MemorySegment out) {
        try {
            MemorySegment result = out.reinterpret(ADDRESS.byteSize());
            result.set(ADDRESS, 0, MemorySegment.NULL);
            JavaComObject obj = resolve(self);
            if (obj != null) {
                MemorySegment u = uuid.reinterpret(16);
                long lo = u.get(JAVA_LONG, 0);
                long hi = u.get(JAVA_LONG, 8);
                for (long[] candidate : obj.supportedIids()) {
                    if (candidate[0] == lo && candidate[1] == hi) {
                        obj.addRef();
                        result.set(ADDRESS, 0, self);
                        return 0; // SLANG_OK
                    }
                }
            }
            return SLANG_E_NO_INTERFACE;
        } catch (Throwable t) {
            return SLANG_E_NO_INTERFACE;
        }
    }

    static int addRefStub(MemorySegment self) {
        try {
            JavaComObject obj = resolve(self);
            return obj == null ? 1 : (int) obj.addRef();
        } catch (Throwable t) {
            return 1;
        }
    }

    static int releaseStub(MemorySegment self) {
        try {
            JavaComObject obj = resolve(self);
            return obj == null ? 0 : (int) obj.release();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ construction helpers

    /**
     * Creates an upcall stub (global arena, once per interface class) for a package-visible
     * static method of {@code owner} whose Java signature matches {@code descriptor}.
     */
    protected static MemorySegment upcall(Class<?> owner, String method, FunctionDescriptor descriptor) {
        try {
            MethodHandle handle = MethodHandles.lookup().findStatic(owner, method, descriptor.toMethodType());
            return FfiSupport.LINKER.upcallStub(handle, descriptor, Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Builds an interface vtable in the global arena from stub slots, base-first. */
    protected static MemorySegment vtable(MemorySegment... slots) {
        MemorySegment vt = Arena.global().allocate(ADDRESS.byteSize() * slots.length, ADDRESS.byteSize());
        for (int i = 0; i < slots.length; i++) {
            vt.setAtIndex(ADDRESS, i, slots[i]);
        }
        return vt;
    }

    /**
     * Parses a canonical IID string (as generated in the {@code ffi.gen} classes) into the two
     * little-endian longs of its {@code SlangUUID} in-memory image
     * ({@code u32 data1, u16 data2, u16 data3, u8 data4[8]}).
     */
    protected static long[] iid(String canonical) {
        String[] parts = canonical.split("-");
        byte[] bytes = new byte[16];
        putLittleEndian(bytes, 0, Long.parseLong(parts[0], 16), 4);
        putLittleEndian(bytes, 4, Long.parseLong(parts[1], 16), 2);
        putLittleEndian(bytes, 6, Long.parseLong(parts[2], 16), 2);
        String tail = parts[3] + parts[4];
        for (int i = 0; i < 8; i++) {
            bytes[8 + i] = (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16);
        }
        return new long[] {littleEndianLong(bytes, 0), littleEndianLong(bytes, 8)};
    }

    private static void putLittleEndian(byte[] dest, int offset, long value, int byteCount) {
        for (int i = 0; i < byteCount; i++) {
            dest[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static long littleEndianLong(byte[] src, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (src[offset + i] & 0xFFL) << (8 * i);
        }
        return v;
    }
}

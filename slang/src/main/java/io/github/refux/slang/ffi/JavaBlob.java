package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.refux.slang.ffi.gen.FfiSupport;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;

/**
 * A Java-implemented {@code ISlangBlob}: refcounted native-visible bytes, typically returned to
 * Slang from {@link JavaFileSystem#load}. The data lives in the object's own arena and is freed
 * when the last native reference is released.
 */
public final class JavaBlob extends JavaComObject {
    private static final long[][] IIDS = {
        iid(io.github.refux.slang.ffi.gen.ISlangBlob.IID), iid(io.github.refux.slang.ffi.gen.ISlangUnknown.IID),
    };

    /** ISlangBlob vtable: IUnknown + getBufferPointer (slot 3) + getBufferSize (slot 4). */
    private static final MemorySegment VTABLE = vtable(
            QUERY_INTERFACE_STUB,
            ADD_REF_STUB,
            RELEASE_STUB,
            upcall(JavaBlob.class, "getBufferPointerStub", FunctionDescriptor.of(ADDRESS, ADDRESS)),
            upcall(JavaBlob.class, "getBufferSizeStub", FunctionDescriptor.of(FfiSupport.C_SIZE_T, ADDRESS)));

    private final MemorySegment data;
    private final long length;

    /** Creates a blob (reference count 1) holding a native copy of {@code bytes}. */
    public JavaBlob(byte[] bytes) {
        super(VTABLE);
        this.length = bytes.length;
        this.data = arena.allocate(Math.max(1, bytes.length));
        MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
    }

    @Override
    protected long[][] supportedIids() {
        return IIDS;
    }

    static MemorySegment getBufferPointerStub(MemorySegment self) {
        try {
            JavaBlob blob = (JavaBlob) resolve(self);
            return blob == null ? MemorySegment.NULL : blob.data;
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    static long getBufferSizeStub(MemorySegment self) {
        try {
            JavaBlob blob = (JavaBlob) resolve(self);
            return blob == null ? 0 : blob.length;
        } catch (Throwable t) {
            return 0;
        }
    }
}

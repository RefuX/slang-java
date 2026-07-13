package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;

/**
 * A Java-implemented {@code ISlangFileSystem}: Slang resolves {@code import}s and
 * {@code #include}s through {@link #load}, receiving contents as {@link JavaBlob}s. Pass
 * {@link #segment()} as {@code SessionDesc.fileSystem}; the session add-refs it, so release the
 * creation reference once the session is created.
 *
 * <p>Vtable: IUnknown (0..2) + {@code ISlangCastable.castAs} (3, answers null by documented
 * contract) + {@code loadFile} (4).
 */
public abstract class JavaFileSystem extends JavaComObject {
    private static final long[][] IIDS = {
        iid(io.github.refux.slang.ffi.gen.ISlangFileSystem.IID),
        iid(io.github.refux.slang.ffi.gen.ISlangCastable.IID),
        iid(io.github.refux.slang.ffi.gen.ISlangUnknown.IID),
    };

    private static final MemorySegment VTABLE = vtable(
            QUERY_INTERFACE_STUB,
            ADD_REF_STUB,
            RELEASE_STUB,
            upcall(JavaFileSystem.class, "castAsStub", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
            upcall(JavaFileSystem.class, "loadFileStub", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)));

    protected JavaFileSystem() {
        super(VTABLE);
    }

    /**
     * Returns the file's contents, or null when it does not exist (mapped to
     * {@code SLANG_E_NOT_FOUND}, which is how Slang probes candidate paths — expect calls for
     * paths that don't exist). Called on the compiling thread; implementations shared across
     * sessions must be safe for concurrent calls.
     */
    protected abstract byte[] load(String path) throws Exception;

    @Override
    protected long[][] supportedIids() {
        return IIDS;
    }

    /** {@code ISlangCastable.castAs}: Java file systems expose no raw casts. */
    static MemorySegment castAsStub(MemorySegment self, MemorySegment guid) {
        return MemorySegment.NULL;
    }

    static int loadFileStub(MemorySegment self, MemorySegment path, MemorySegment outBlob) {
        try {
            MemorySegment out = outBlob.reinterpret(ADDRESS.byteSize());
            out.set(ADDRESS, 0, MemorySegment.NULL);
            JavaFileSystem fs = (JavaFileSystem) resolve(self);
            if (fs == null) {
                return SLANG_E_NOT_FOUND;
            }
            byte[] data = fs.load(SlangNative.readUtf8(path));
            if (data == null) {
                return SLANG_E_NOT_FOUND;
            }
            // The blob's creation reference transfers to the caller, per COM out-param rules.
            out.set(ADDRESS, 0, new JavaBlob(data).segment());
            return 0; // SLANG_OK
        } catch (Throwable t) {
            return SLANG_E_NOT_FOUND;
        }
    }
}

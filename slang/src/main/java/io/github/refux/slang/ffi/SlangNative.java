package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.refux.slang.loader.SlangLibrary;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Hand-written M0 downcall bindings for the {@code extern "C"} entry points of slang.h that the
 * walking skeleton needs. Replaced wholesale by generated code in milestone M2 (DESIGN.md §9).
 */
public final class SlangNative {
    private SlangNative() {}

    static final Linker LINKER = Linker.nativeLinker();

    /** {@code const char* spGetBuildTagString(void)} */
    private static final MethodHandle SP_GET_BUILD_TAG_STRING =
            downcall("spGetBuildTagString", FunctionDescriptor.of(ADDRESS));

    /**
     * {@code SlangResult slang_createGlobalSession2(const SlangGlobalSessionDesc* desc,
     *                                               slang::IGlobalSession** outGlobalSession)}
     */
    private static final MethodHandle SLANG_CREATE_GLOBAL_SESSION2 =
            downcall("slang_createGlobalSession2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(SlangLibrary.get().find(symbol), descriptor);
    }

    /** Returns the build tag of the loaded Slang library, e.g. {@code "2026.13"}. */
    public static String spGetBuildTagString() {
        try {
            return readUtf8((MemorySegment) SP_GET_BUILD_TAG_STRING.invokeExact());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns a {@code SlangResult}; on success {@code outGlobalSession} holds the new session. */
    public static int slang_createGlobalSession2(MemorySegment desc, MemorySegment outGlobalSession) {
        try {
            return (int) SLANG_CREATE_GLOBAL_SESSION2.invokeExact(desc, outGlobalSession);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code SLANG_SUCCEEDED}: a {@code SlangResult} is a success code iff it is non-negative. */
    public static boolean succeeded(int slangResult) {
        return slangResult >= 0;
    }

    /** {@code SLANG_FAIL} — the generic failure result (COM {@code E_FAIL}). */
    public static final int SLANG_FAIL = 0x80004005;

    /**
     * Reads a NUL-terminated UTF-8 C string. The segment is typically a zero-length segment read
     * from native memory, so it is first re-interpreted as unbounded; the read stops at the NUL.
     * Returns null for a NULL pointer.
     */
    public static String readUtf8(MemorySegment cString) {
        if (cString == null || cString.address() == 0) {
            return null;
        }
        return cString.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * Downcall {@link MethodHandle}s declare {@link Throwable}; nothing checked can actually
     * escape a native call, so anything caught is wrapped as an unchecked error.
     */
    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException("Unexpected exception from native call", t);
    }
}

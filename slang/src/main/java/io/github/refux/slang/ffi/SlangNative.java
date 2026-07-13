package io.github.refux.slang.ffi;

import io.github.refux.slang.ffi.gen.SlangAPI;
import java.lang.foreign.MemorySegment;

/**
 * Hand-written conveniences over the generated C-export bindings ({@code ffi.gen.SlangAPI}).
 * Since M2 all dispatch is generated; only string decoding, result helpers, and exception
 * plumbing live here.
 */
public final class SlangNative {
    private SlangNative() {}

    /** Returns the build tag of the loaded Slang library, e.g. {@code "2026.13"}. */
    public static String spGetBuildTagString() {
        return readUtf8(SlangAPI.spGetBuildTagString());
    }

    /** Returns a {@code SlangResult}; on success {@code outGlobalSession} holds the new session. */
    public static int slang_createGlobalSession2(MemorySegment desc, MemorySegment outGlobalSession) {
        return SlangAPI.slang_createGlobalSession2(desc, outGlobalSession);
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
     * Downcall {@link java.lang.invoke.MethodHandle}s declare {@link Throwable}; nothing checked
     * can actually escape a native call, so anything caught is wrapped as an unchecked error.
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

package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * M0 micro-binding of {@code slang::IGlobalSession} — just enough surface to prove COM vtable
 * dispatch end-to-end. Slot numbers are from the v2026.13 header scan (DESIGN.md Appendix A);
 * the M2 generator emits the full 33-slot interface.
 */
public final class IGlobalSession extends IUnknown {
    /** Vtable slot of {@code const char* getBuildTagString()}. */
    public static final int VT_GET_BUILD_TAG_STRING = 8;

    /** {@code const char* (*)(void* self)} */
    private static final MethodHandle MH_ADDRESS_OF_SELF =
        SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(ADDRESS, ADDRESS));

    public IGlobalSession(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Creates a global session via {@code slang_createGlobalSession2} with default descriptor
     * values ({@code SLANG_API_VERSION}, language version 2025, GLSL support off).
     */
    public static IGlobalSession create() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = SlangGlobalSessionDesc.allocate(arena);
            MemorySegment out = arena.allocate(ADDRESS);
            int result = SlangNative.slang_createGlobalSession2(desc, out);
            if (!SlangNative.succeeded(result)) {
                throw new IllegalStateException(
                    "slang_createGlobalSession2 failed: 0x" + Integer.toHexString(result));
            }
            // The pointer value read here has global scope; it stays valid after the arena closes.
            return new IGlobalSession(out.get(ADDRESS, 0));
        }
    }

    /**
     * The COM-path twin of {@link SlangNative#spGetBuildTagString()}: same string, but fetched
     * through vtable slot {@value #VT_GET_BUILD_TAG_STRING}. The M0 smoke test asserts the two
     * agree, which proves the slot arithmetic and calling convention on each platform.
     */
    public String getBuildTagString() {
        try {
            return SlangNative.readUtf8((MemorySegment) MH_ADDRESS_OF_SELF.invokeExact(
                fnPtr(VT_GET_BUILD_TAG_STRING), segment()));
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }
}

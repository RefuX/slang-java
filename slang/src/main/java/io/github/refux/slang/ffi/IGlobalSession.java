package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.refux.slang.SlangException;
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
    public static final int VT_CREATE_SESSION = 3;
    public static final int VT_FIND_PROFILE = 4;
    /** Vtable slot of {@code const char* getBuildTagString()}. */
    public static final int VT_GET_BUILD_TAG_STRING = 8;

    /** {@code const char* (*)(void* self)} */
    private static final MethodHandle MH_ADDRESS_OF_SELF =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code SlangResult createSession(SessionDesc const& desc, ISession** outSession)} */
    private static final MethodHandle MH_CREATE_SESSION =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** {@code SlangProfileID findProfile(char const* name)} — SlangProfileID is a uint32. */
    private static final MethodHandle MH_FIND_PROFILE =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

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
                throw new IllegalStateException("slang_createGlobalSession2 failed: 0x" + Integer.toHexString(result));
            }
            // The pointer value read here has global scope; it stays valid after the arena closes.
            return new IGlobalSession(out.get(ADDRESS, 0));
        }
    }

    /**
     * Creates a compilation session from a {@link SessionDesc} segment. Slang copies the
     * descriptor's contents during this call (verified by {@code CompilePipelineTest}), so the
     * descriptor's arena may be closed as soon as this returns.
     */
    public ISession createSession(MemorySegment sessionDesc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int result = (int) MH_CREATE_SESSION.invokeExact(fnPtr(VT_CREATE_SESSION), segment(), sessionDesc, out);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("IGlobalSession::createSession failed", result);
            }
            return new ISession(out.get(ADDRESS, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }

    /**
     * Looks up a profile id (e.g. {@code "spirv_1_5"}, {@code "cs_5_0"}) for
     * {@link TargetDesc#setProfile}. Profile ids are not stable across Slang versions, so they
     * must always be looked up at runtime by name. Returns {@code SLANG_PROFILE_UNKNOWN} (0)
     * for unknown names.
     */
    public int findProfile(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) MH_FIND_PROFILE.invokeExact(fnPtr(VT_FIND_PROFILE), segment(), arena.allocateFrom(name));
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }

    /**
     * The COM-path twin of {@link SlangNative#spGetBuildTagString()}: same string, but fetched
     * through vtable slot {@value #VT_GET_BUILD_TAG_STRING}. The M0 smoke test asserts the two
     * agree, which proves the slot arithmetic and calling convention on each platform.
     */
    public String getBuildTagString() {
        try {
            return SlangNative.readUtf8(
                    (MemorySegment) MH_ADDRESS_OF_SELF.invokeExact(fnPtr(VT_GET_BUILD_TAG_STRING), segment()));
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }
}

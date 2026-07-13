package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Wrapper for {@code slang::IGlobalSession}: session creation, profile lookup, and the build-tag
 * probe. Raw vtable dispatch lives in the generated {@code ffi.gen.IGlobalSession}.
 */
public final class IGlobalSession extends IUnknown {

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
                throw new SlangException("slang_createGlobalSession2 failed: 0x" + Integer.toHexString(result), result);
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
            int result = io.github.refux.slang.ffi.gen.IGlobalSession.createSession(segment(), sessionDesc, out);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("IGlobalSession::createSession failed", result);
            }
            return new ISession(out.get(ADDRESS, 0));
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
            return io.github.refux.slang.ffi.gen.IGlobalSession.findProfile(segment(), arena.allocateFrom(name));
        }
    }

    /**
     * The COM-path twin of {@link SlangNative#spGetBuildTagString()}: same string, but fetched
     * through the vtable. The M0 smoke test asserts the two agree, which proves the slot
     * arithmetic and calling convention on each platform.
     */
    public String getBuildTagString() {
        return SlangNative.readUtf8(io.github.refux.slang.ffi.gen.IGlobalSession.getBuildTagString(segment()));
    }
}

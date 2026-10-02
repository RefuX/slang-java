package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangException;
import io.github.refux.slang.ffi.gen.SlangPassThrough;
import io.github.refux.slang.loader.SlangLibrary;
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
            IGlobalSession global = new IGlobalSession(out.get(ADDRESS, 0));
            global.pinDownstreamCompilersToPayload();
            return global;
        }
    }

    /**
     * The payload directory as a native string, allocated once per process, or {@code null} when
     * the library came from the platform's search path. Held globally because Slang's lifetime
     * contract for the string handed to {@code setDownstreamCompilerPath} is not documented, so it
     * must outlive the call.
     */
    private static final class DownstreamPath {
        static final MemorySegment SEGMENT = SlangLibrary.get()
                .directory()
                .map(dir -> Arena.global().allocateFrom(dir.toString()))
                .orElse(null);
    }

    /**
     * Points the downstream compilers that live in the payload's companion libraries at the
     * directory this build's compiler library was loaded from.
     *
     * <p>Slang loads {@code slang-glslang} — which backs glslang, spirv-dis and spirv-opt — lazily
     * and by bare name, so it resolves against the platform's search order. On Windows that order
     * never includes {@code slang-compiler.dll}'s own directory and there is no {@code
     * RPATH=$ORIGIN} to stand in for it, so a payload shipping {@code slang-glslang.dll} beside the
     * compiler still fails with {@code failed to load dynamic library 'slang-glslang'} — targets
     * like {@link io.github.refux.slang.ffi.gen.SlangCompileTarget#SLANG_SPIRV_ASM} then fail
     * unless the host happens to have a Slang or Vulkan SDK on PATH. Setting the path fixes
     * Windows and, on every platform, keeps the pinned payload's companions ahead of the host's —
     * the same pinning {@link SlangLibrary}'s class-path case already promises.
     */
    private void pinDownstreamCompilersToPayload() {
        MemorySegment payloadDir = DownstreamPath.SEGMENT;
        if (payloadDir == null) {
            return; // Loaded off the platform search path; the host's own search order applies.
        }
        for (int passThrough : new int[] {
            SlangPassThrough.SLANG_PASS_THROUGH_GLSLANG,
            SlangPassThrough.SLANG_PASS_THROUGH_SPIRV_DIS,
            SlangPassThrough.SLANG_PASS_THROUGH_SPIRV_OPT
        }) {
            io.github.refux.slang.ffi.gen.IGlobalSession.setDownstreamCompilerPath(segment(), passThrough, payloadDir);
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

    /** {@code SLANG_OK} when this build can produce {@code compileTarget} (a {@code SlangCompileTarget}). */
    public int checkCompileTargetSupport(int compileTarget) {
        return io.github.refux.slang.ffi.gen.IGlobalSession.checkCompileTargetSupport(segment(), compileTarget);
    }

    /** {@code SLANG_OK} when the downstream compiler {@code passThrough} (a {@code SlangPassThrough}) is available. */
    public int checkPassThroughSupport(int passThrough) {
        return io.github.refux.slang.ffi.gen.IGlobalSession.checkPassThroughSupport(segment(), passThrough);
    }

    /**
     * Looks up a capability id by name (e.g. {@code "spirv_1_5"}), as the {@code Capability} compiler
     * option takes it. Returns {@code SLANG_CAPABILITY_UNKNOWN} (0) for unknown names.
     */
    public int findCapability(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return io.github.refux.slang.ffi.gen.IGlobalSession.findCapability(segment(), arena.allocateFrom(name));
        }
    }

    /**
     * The on-disk path of the library Slang would load for the downstream compiler
     * {@code passThrough}, or null when there is none: {@code SLANG_E_NOT_FOUND} (not located) and
     * {@code SLANG_E_NOT_AVAILABLE} (loaded, but not from a shared library — executable-based
     * compilers such as Clang, GCC or Metal) both mean no path. Added in Slang 2026.17, in the vtable
     * slot that held {@code getDownstreamCompilerVersion} before it.
     */
    public String getDownstreamCompilerPath(int passThrough) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPath = arena.allocate(ADDRESS);
            int result = io.github.refux.slang.ffi.gen.IGlobalSession.getDownstreamCompilerPath(
                    segment(), passThrough, outPath);
            if (result == SlangNative.SLANG_E_NOT_FOUND || result == SlangNative.SLANG_E_NOT_AVAILABLE) {
                return null;
            }
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("IGlobalSession::getDownstreamCompilerPath failed", result);
            }
            try (ISlangBlob path = new ISlangBlob(outPath.get(ADDRESS, 0))) {
                return path.toUtf8String();
            }
        }
    }
}

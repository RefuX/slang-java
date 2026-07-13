package io.github.refux.slang;

import io.github.refux.slang.ffi.IGlobalSession;

/**
 * The process-level Slang compiler instance ({@code slang::IGlobalSession}): the factory for
 * compilation {@link Session}s and the authority for profile lookups. Create it once via
 * {@link Slang#createGlobalSession()} and share it; it is safe to use from multiple threads for
 * session creation, while each created {@link Session} stays confined to one thread at a time.
 */
public final class GlobalSession extends NativeObject {
    private final IGlobalSession global;

    GlobalSession(IGlobalSession global) {
        super(global);
        this.global = global;
    }

    /** Starts configuring a new compilation session; call {@link SessionBuilder#create()}. */
    public SessionBuilder newSession() {
        handle();
        return new SessionBuilder(this);
    }

    /**
     * Looks up a profile id by name (e.g. {@code "spirv_1_5"}, {@code "cs_5_0"}). Profile ids
     * are not stable across Slang versions, so they are always resolved at runtime. Returns 0
     * ({@code SLANG_PROFILE_UNKNOWN}) for unknown names.
     */
    public int findProfile(String name) {
        return ffi().findProfile(name);
    }

    /** The Slang build tag of the loaded native library, e.g. {@code "2026.13"}. */
    public String buildTagString() {
        return ffi().getBuildTagString();
    }

    /** Aliveness-checked access for package internals and ffi-layer interop. */
    IGlobalSession ffi() {
        handle();
        return global;
    }
}

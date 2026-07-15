package io.github.refux.slang;

import io.github.refux.slang.ffi.IGlobalSession;

/**
 * The process-level Slang compiler instance ({@code slang::IGlobalSession}): the factory for
 * compilation {@link Session}s and the authority for profile lookups. Create it once via
 * {@link Slang#createGlobalSession()} and share it; it is safe to use from multiple threads for
 * session creation, while each created {@link Session} stays confined to one thread at a time.
 */
// Returns owned Session wrappers (which manage their own native handle) and borrows the global
// session's own handle; nothing leaks, but the resource inspection can't see the transfer.
@SuppressWarnings("resource")
public final class GlobalSession extends NativeObject {
    /**
     * A module trivial enough to compile in any Slang build, used to observe the serialized-module
     * version this one writes. Its content is irrelevant beyond being valid and tiny.
     */
    private static final String VERSION_PROBE_MODULE = "_slang_java_version_probe";

    private static final String VERSION_PROBE_SOURCE =
            "module " + VERSION_PROBE_MODULE + ";\npublic int probe() { return 1; }\n";

    private final IGlobalSession global;

    /** Lazily observed; -1 until {@link #supportedModuleVersion()} has run once. */
    private volatile long supportedModuleVersion = -1;

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

    /**
     * The serialized-module version this Slang build writes — and therefore one it reads. IR whose
     * {@link ModuleInfo#moduleVersion()} equals this loads; {@link Session#loadModuleFromIr} checks
     * exactly that before handing bytes to native code.
     *
     * <p>Note this is the version written, which may be narrower than the set read: a build might
     * also accept older versions. Slang knows the real range — {@code slangc
     * -get-supported-module-versions} prints it — but exports no API to ask, so a binding cannot
     * read it. The written version is the one value that is certainly readable, so it is used as a
     * conservative test: the cost of rejecting IR that would in fact have loaded is recompiling it
     * from source, whereas the cost of accepting IR that would not is the process aborting with no
     * diagnostic.
     *
     * <p>Observed once, by compiling a trivial module and reading back what it serialized to; the
     * result is cached for the life of this global session.
     *
     * @return the serialized-module version this build emits
     */
    public long supportedModuleVersion() {
        long observed = supportedModuleVersion;
        if (observed >= 0) {
            return observed;
        }
        synchronized (this) {
            if (supportedModuleVersion < 0) {
                try (Session probe = newSession().target(CompileTarget.SPIRV).create()) {
                    byte[] ir = probe.loadModuleFromSource(VERSION_PROBE_MODULE, VERSION_PROBE_SOURCE)
                            .serialize();
                    supportedModuleVersion = probe.moduleInfo(ir).moduleVersion();
                }
            }
            return supportedModuleVersion;
        }
    }

    /** Aliveness-checked access for package internals and ffi-layer interop. */
    IGlobalSession ffi() {
        handle();
        return global;
    }
}

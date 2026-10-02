package io.github.refux.slang;

import io.github.refux.slang.ffi.IGlobalSession;
import io.github.refux.slang.ffi.SlangNative;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** The Slang build tag of the loaded native library, e.g. {@code "2026.14.1"}. */
    public String buildTagString() {
        return ffi().getBuildTagString();
    }

    /**
     * Looks up a capability id by name (e.g. {@code "spirv_1_5"}, {@code "SPV_KHR_ray_query"}).
     * {@link SessionBuilder#capability(String)} takes the name directly; this is for checking one
     * up front. Returns 0 ({@code SLANG_CAPABILITY_UNKNOWN}) for unknown names.
     */
    public int findCapability(String name) {
        return ffi().findCapability(name);
    }

    /**
     * Whether this Slang build can produce {@code target}: false when the target needs a downstream
     * compiler that cannot be found (DXC for {@link CompileTarget#DXIL}, say).
     */
    public boolean isTargetSupported(CompileTarget target) {
        return SlangNative.succeeded(ffi().checkCompileTargetSupport(target.value()));
    }

    /** Whether the downstream compiler {@code passThrough} is available to this Slang build. */
    public boolean isPassThroughSupported(PassThrough passThrough) {
        return SlangNative.succeeded(ffi().checkPassThroughSupport(Enums.raw(passThrough, passThrough.value())));
    }

    /**
     * Where the library Slang would load for the downstream compiler {@code passThrough} lives,
     * found the same way compilation finds it — e.g. the {@code slang-glslang} in the natives
     * payload for {@link PassThrough#GLSLANG}, or the exact NVRTC that would compile CUDA.
     *
     * @return the path, or empty when the compiler cannot be found or is not a shared library
     *     (executable-based compilers such as Clang, GCC or Metal)
     */
    public Optional<Path> downstreamCompilerPath(PassThrough passThrough) {
        return Optional.ofNullable(ffi().getDownstreamCompilerPath(Enums.raw(passThrough, passThrough.value())))
                .map(Path::of);
    }

    /**
     * The serialized-module version this Slang build writes — and therefore one it certainly reads.
     *
     * <p>A build may read a range of versions, of which this is only the newest. Slang knows the
     * real range — {@code slangc -get-supported-module-versions} prints it — but exports no API to
     * ask. Since 2026.18.3 that does not matter: Slang checks the version itself on load, and
     * {@link Session#loadModuleFromIr} defers to it. Against older releases, which abort the process
     * on IR they cannot read, {@link Session#loadModuleFromIr} lets only this version through: the
     * cost of rejecting IR that would in fact have loaded is recompiling it from source, whereas the
     * cost of accepting IR that would not is the process aborting with no diagnostic.
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

    /**
     * Whether this library checks a serialized module's format and version before deserializing
     * it, reporting IR it cannot read as an error. Slang does from 2026.18.3 (upstream #12905);
     * older releases abort the process instead. Read from the build tag, so a build whose tag is no
     * release number (a local build without git tags, say) counts as an older one.
     */
    boolean validatesModuleIrOnLoad() {
        return releasedAtLeast(buildTagString(), 2026, 18, 3);
    }

    // Release tags are year.release[.patch]; a local build appends git-describe noise
    // ("2026.19-12-gdeadbee"). Bounded digits keep parseInt from overflowing on junk.
    private static final Pattern RELEASE_TAG = Pattern.compile("v?(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?.*");

    /** Whether {@code buildTag} names release {@code year.release.patch} or later; false if no release. */
    static boolean releasedAtLeast(String buildTag, int year, int release, int patch) {
        Matcher tag = RELEASE_TAG.matcher(buildTag == null ? "" : buildTag);
        if (!tag.matches()) {
            return false;
        }
        int[] named = {
            Integer.parseInt(tag.group(1)),
            Integer.parseInt(tag.group(2)),
            tag.group(3) == null ? 0 : Integer.parseInt(tag.group(3))
        };
        return Arrays.compare(named, new int[] {year, release, patch}) >= 0;
    }

    /** Aliveness-checked access for package internals and ffi-layer interop. */
    IGlobalSession ffi() {
        handle();
        return global;
    }
}

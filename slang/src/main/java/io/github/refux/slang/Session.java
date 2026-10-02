package io.github.refux.slang;

import io.github.refux.slang.ffi.ISession;
import io.github.refux.slang.ffi.SlangNative;
import java.util.function.Consumer;

/**
 * A compilation scope: loads modules, composes them with entry points, and owns everything
 * loaded through it (modules stay valid until the session closes).
 *
 * <p><b>Threading:</b> a session and the objects created from it are confined to one thread at a
 * time (Slang does not synchronize internally). With {@code -Dio.github.refux.slang.debug=true}
 * the confinement is asserted; concurrent compilation wants one session per thread
 * (DESIGN.md §11).
 */
// Uses the session's own (NativeObject-managed) native handle and hands newly-created owned
// handles (modules, composites, conformances) straight to NativeObject wrappers, so no
// AutoCloseable leaks — the resource inspection just can't see the ownership transfer.
@SuppressWarnings("resource")
public final class Session extends NativeObject {
    private final GlobalSession global;
    private final ISession session;
    private final Consumer<String> onDiagnostics;
    private final Thread owner = Thread.currentThread();

    Session(GlobalSession global, ISession session, Consumer<String> onDiagnostics) {
        super(session);
        this.global = global;
        this.session = session;
        this.onDiagnostics = onDiagnostics;
    }

    /**
     * Compiles Slang source text into a {@link Module} named {@code name} (the name other
     * modules would {@code import} it by; diagnostics reference {@code <name>.slang}).
     *
     * @throws SlangCompileException with the compiler's diagnostics text when compilation fails;
     *     success-with-warnings text goes to the
     *     {@link SessionBuilder#onDiagnostics(java.util.function.Consumer)} consumer
     */
    public Module loadModuleFromSource(String name, String source) {
        checkThread();
        return new Module(this, session.loadModuleFromSourceString(name, name + ".slang", source, onDiagnostics));
    }

    /**
     * Loads a module by the name an {@code import} would use (e.g. {@code "lighting"}, or
     * {@code "materials.pbr"} for {@code materials/pbr.slang}), finding its source through the
     * session's {@link SessionBuilder#searchPath(java.nio.file.Path...) search paths} and
     * {@link SessionBuilder#fileSystem(SlangFileSystem) file system}. A module already loaded under that name is
     * returned again rather than recompiled.
     *
     * @throws SlangCompileException with the compiler's diagnostics when the module cannot be found
     *     or fails to compile; success-with-warnings text goes to the
     *     {@link SessionBuilder#onDiagnostics(java.util.function.Consumer)} consumer
     */
    public Module loadModule(String name) {
        checkThread();
        return new Module(this, session.loadModule(name, onDiagnostics));
    }

    /**
     * Whether serialized IR is still current for this session: written by this Slang build with
     * this session's compiler options, from source files whose contents have not changed since. A
     * cache of {@link Module#serialize()} output can use it to decide between
     * {@link #loadModuleFromIr} and recompiling.
     *
     * <p>Source files are read through this session, which keeps what it has already read: an edit
     * made after the session read a file goes unseen, so check with a fresh session. And one case
     * answers {@code true} without comparing the build or options: when the module's own source
     * file cannot be found at all, Slang treats the IR as a standalone precompiled artifact.
     * {@link #loadModuleFromIr} still refuses IR this build cannot read.
     *
     * @param modulePath where the serialized module lives (or would); the source files recorded in
     *     it are looked up relative to this and on the session's search paths
     * @param ir the serialized module bytes
     */
    public boolean isIrUpToDate(String modulePath, byte[] ir) {
        checkThread();
        return session.isBinaryModuleUpToDate(modulePath, ir);
    }

    /**
     * Reads what {@code ir} declares about itself — its serialized-module version, the Slang build
     * that wrote it, and its name — without loading it.
     *
     * <p>Safe on IR this build cannot load, which is the point: it lets a caller decide between
     * {@link #loadModuleFromIr} and recompiling from source without risking the abort described
     * there. {@link #loadModuleFromIr} applies this check itself, so callers only need this to
     * choose a strategy up front (e.g. to skip reading a large IR file at all).
     *
     * @param ir the serialized module bytes to inspect
     * @return what the IR declares: its module version, the Slang build that wrote it, and its name
     * @throws SlangException when {@code ir} is not a readable serialized module — including IR in a
     *     serialization format this build no longer reads, which carries result
     *     {@code SLANG_E_NOT_AVAILABLE}
     */
    public ModuleInfo moduleInfo(byte[] ir) {
        checkThread();
        return session.loadModuleInfoFromIrBlob(ir);
    }

    /**
     * Loads a module from {@link Module#serialize() serialized} checked IR, skipping parse and
     * type-check. Other modules {@code import} it by {@code name}. Serialized IR is tied to the
     * Slang that wrote it — a build reads only a range of module versions — and IR this build
     * cannot read throws {@link SlangCompileException}, after which the caller should recompile
     * the module from source.
     *
     * <p>Which versions load is Slang's call. Since 2026.18.3 Slang checks a module's format and
     * version before deserializing it and reports what it cannot read, so any version it accepts
     * loads, including versions older than the one it writes. Releases before that <em>abort the
     * process</em> on IR whose module version they do not read — no exception, no diagnostic, no
     * {@code hs_err} — so against those only {@link GlobalSession#supportedModuleVersion()} is let
     * through to native code. Either way the IR's metadata is read first ({@link #moduleInfo}), so a
     * serialization format the build no longer reads fails with the same actionable exception.
     */
    public Module loadModuleFromIr(String name, byte[] ir) {
        checkThread();
        ModuleInfo info;
        try {
            info = session.loadModuleInfoFromIrBlob(ir);
        } catch (SlangException e) {
            if (e.result() != SlangNative.SLANG_E_NOT_AVAILABLE) {
                throw e;
            }
            // A retired serialization format (2026.18.3 stopped reading format 1) hides its metadata
            // too, so there is no version or writer to report: only the module and the remedy.
            throw new SlangCompileException(
                    "cannot load serialized module '" + name + "': it was written in a serialization format"
                            + " this build (Slang " + global.buildTagString() + ") no longer reads."
                            + " Recompile it from source.",
                    e.result());
        }
        long written = global.supportedModuleVersion();
        if (info.moduleVersion() != written && !global.validatesModuleIrOnLoad()) {
            throw new SlangCompileException(
                    "cannot load serialized module '" + info.name() + "': it is module version "
                            + info.moduleVersion() + ", written by Slang " + info.compilerVersion()
                            + ", but this build (Slang " + global.buildTagString() + ") reads module version "
                            + written + ". Recompile it from source.",
                    SlangNative.SLANG_FAIL);
        }
        try {
            return new Module(this, session.loadModuleFromIrBlob(name, name + ".slang-module", ir));
        } catch (SlangCompileException e) {
            if (info.moduleVersion() == written) {
                throw e; // not a cross-version load, so Slang's diagnostics say it all
            }
            // Slang refused IR from another version (E00130), or failed on it for some other reason;
            // either way the remedy is the same, and its own diagnostics follow.
            throw new SlangCompileException(
                    "cannot load serialized module '" + info.name() + "': it is module version "
                            + info.moduleVersion() + ", written by Slang " + info.compilerVersion()
                            + ", and this build (Slang " + global.buildTagString() + ") writes module version "
                            + written + ". Recompile it from source.\n" + e.getMessage(),
                    e.result());
        }
    }

    /**
     * Records that {@code type} implements {@code interfaceType}, returning a component that —
     * composited and linked with a program — makes the type's witness table available for dynamic
     * dispatch (existentials read from a buffer). Get the {@link TypeReflection}s from a module's
     * {@link ComponentType#layout(long) layout} via {@link ShaderReflection#findTypeByName(String)}.
     *
     * @param conformanceId the dispatch id to pin, or {@code -1} to auto-assign (registration order)
     */
    public TypeConformance createTypeConformance(
            TypeReflection type, TypeReflection interfaceType, long conformanceId) {
        checkThread();
        return new TypeConformance(
                this, session.createTypeConformance(type.segment(), interfaceType.segment(), conformanceId));
    }

    /**
     * The 16-byte RTTI header identifying {@code type}'s conformance to {@code interfaceType}, to be
     * written at the start of that value's element in a {@code StructuredBuffer<Interface>} for dynamic
     * dispatch (the concrete payload follows it; the sequential dispatch id from
     * {@link #createTypeConformance} occupies bytes 8-11). Register the conformance first so the id is
     * assigned.
     *
     * @param type the concrete type
     * @param interfaceType the interface it conforms to
     * @return the 16 header bytes
     */
    public byte[] getDynamicObjectRTTIBytes(TypeReflection type, TypeReflection interfaceType) {
        checkThread();
        return session.getDynamicObjectRTTIBytes(type.segment(), interfaceType.segment());
    }

    /**
     * Combines modules and entry points into one unit of shader code; the order determines the
     * parameter layout order. The result is typically {@link ComponentType#link() linked} next.
     */
    public ComponentType composite(ComponentType... components) {
        checkThread();
        io.github.refux.slang.ffi.IComponentType[] handles =
                new io.github.refux.slang.ffi.IComponentType[components.length];
        for (int i = 0; i < components.length; i++) {
            handles[i] = components[i].componentHandle();
        }
        return new ComponentType(this, session.createCompositeComponentType(handles));
    }

    /** Aliveness + (in debug mode) thread-confinement check for session-scoped operations. */
    void checkThread() {
        handle();
        if (NativeObject.DEBUG && Thread.currentThread() != owner) {
            throw new IllegalStateException("Session is confined to " + owner + " but was used from "
                    + Thread.currentThread()
                    + "; sessions are not thread-safe (DESIGN.md §11)");
        }
    }
}

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
     * @throws SlangException when {@code ir} is not a readable serialized module
     */
    public ModuleInfo moduleInfo(byte[] ir) {
        checkThread();
        return session.loadModuleInfoFromIrBlob(ir);
    }

    /**
     * Loads a module from {@link Module#serialize() serialized} checked IR, skipping parse and
     * type-check. Other modules {@code import} it by {@code name}. The IR is only readable by a
     * compatible Slang build; a mismatch throws {@link SlangCompileException}, and the caller
     * should recompile the module from source.
     *
     * <p>Compatibility is checked before the bytes reach native code, via {@link #moduleInfo}. That
     * check is not defensive programming: Slang <em>aborts the process</em> on IR whose module
     * version it does not read — no exception, no diagnostic, no {@code hs_err} — so a mismatch
     * cannot be caught after the fact.
     */
    public Module loadModuleFromIr(String name, byte[] ir) {
        checkThread();
        ModuleInfo info = session.loadModuleInfoFromIrBlob(ir);
        long supported = global.supportedModuleVersion();
        if (info.moduleVersion() != supported) {
            throw new SlangCompileException(
                    "cannot load serialized module '" + info.name() + "': it is module version "
                            + info.moduleVersion() + ", written by Slang " + info.compilerVersion()
                            + ", but this build (Slang " + global.buildTagString() + ") reads module version "
                            + supported + ". Recompile it from source.",
                    SlangNative.SLANG_FAIL);
        }
        return new Module(this, session.loadModuleFromIrBlob(name, name + ".slang-module", ir));
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

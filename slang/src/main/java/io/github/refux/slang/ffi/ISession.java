package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.ModuleInfo;
import io.github.refux.slang.SlangCompileException;
import io.github.refux.slang.SlangException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.Consumer;

/**
 * Wrapper for {@code slang::ISession} — module loading and composition. Raw vtable dispatch
 * lives in the generated {@code ffi.gen.ISession}.
 *
 * <p>Not thread-safe: confine a session and everything loaded through it to one thread at a time
 * (DESIGN.md §11).
 */
public final class ISession extends IUnknown {

    ISession(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Compiles Slang source text into a module. The returned wrapper is borrowed — the session
     * owns its modules, and they stay valid until the session is closed.
     *
     * @param moduleName the name other modules would {@code import} this one by
     * @param path a synthetic file path used in diagnostics (e.g. {@code "hello.slang"})
     * @throws SlangCompileException with the compiler's diagnostics text when compilation fails
     */
    public IModule loadModuleFromSourceString(String moduleName, String path, String source) {
        return loadModuleFromSourceString(moduleName, path, source, null);
    }

    /**
     * As {@link #loadModuleFromSourceString(String, String, String)}, additionally delivering
     * success diagnostics (warnings) to {@code onDiagnostics} when the compiler produced any and
     * the consumer is non-null.
     */
    public IModule loadModuleFromSourceString(
            String moduleName, String path, String source, Consumer<String> onDiagnostics) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outDiag = arena.allocate(ADDRESS);
            MemorySegment module = io.github.refux.slang.ffi.gen.ISession.loadModuleFromSourceString(
                    segment(),
                    arena.allocateFrom(moduleName),
                    arena.allocateFrom(path),
                    arena.allocateFrom(source),
                    outDiag);
            String diagnostics = Diagnostics.consume(outDiag);
            if (module.address() == 0) {
                throw new SlangCompileException(
                        diagnostics != null ? diagnostics : "module compilation failed", SlangNative.SLANG_FAIL);
            }
            if (diagnostics != null && onDiagnostics != null) {
                onDiagnostics.accept(diagnostics);
            }
            return new IModule(module);
        }
    }

    /**
     * Combines modules and entry points into one unit of shader code; the result (caller-owned)
     * is what gets {@link IComponentType#link() linked} and compiled. The order of components
     * determines parameter layout order.
     */
    public IComponentType createCompositeComponentType(IComponentType... components) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment array = arena.allocate(ADDRESS, components.length);
            for (int i = 0; i < components.length; i++) {
                array.setAtIndex(ADDRESS, i, components[i].segment());
            }
            MemorySegment outComposite = arena.allocate(ADDRESS);
            MemorySegment outDiag = arena.allocate(ADDRESS);
            int result = io.github.refux.slang.ffi.gen.ISession.createCompositeComponentType(
                    segment(), array, components.length, outComposite, outDiag);
            Diagnostics.check("ISession::createCompositeComponentType", result, outDiag);
            return new IComponentType(outComposite.get(ADDRESS, 0));
        }
    }

    /**
     * Loads a module from previously {@link IModule#serialize() serialized} checked IR, skipping
     * parse and type-check. The returned module is borrowed (owned by the session).
     *
     * @param moduleName the name other modules import it by
     * @param path a synthetic path used in diagnostics
     * @param ir the serialized module bytes
     */
    public IModule loadModuleFromIrBlob(String moduleName, String path, byte[] ir) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment irData = arena.allocate(ir.length);
            MemorySegment.copy(ir, 0, irData, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, ir.length);
            // slang_createBlob copies the bytes into a native-owned blob, so irData's arena may close.
            MemorySegment blob = io.github.refux.slang.ffi.gen.SlangAPI.slang_createBlob(irData, ir.length);
            MemorySegment outDiag = arena.allocate(ADDRESS);
            MemorySegment module = io.github.refux.slang.ffi.gen.ISession.loadModuleFromIRBlob(
                    segment(), arena.allocateFrom(moduleName), arena.allocateFrom(path), blob, outDiag);
            String diagnostics = Diagnostics.consume(outDiag);
            if (module.address() == 0) {
                throw new SlangCompileException(
                        diagnostics != null ? diagnostics : "module IR load failed", SlangNative.SLANG_FAIL);
            }
            return new IModule(module);
        }
    }

    /**
     * Reads a serialized module's name and versions without loading it, wrapping
     * {@code slang_loadModuleInfoFromIRBlob}.
     *
     * <p>This is the only safe way to ask whether {@link #loadModuleFromIrBlob} would succeed:
     * Slang aborts the process (Windows {@code STATUS_STACK_BUFFER_OVERRUN}, POSIX {@code SIGABRT})
     * when handed IR whose module version it does not read, so the question cannot be answered by
     * trying the load and catching a failure — no Java runs on that path. This query is safe on
     * exactly that IR.
     *
     * @param ir the serialized module bytes
     * @return the module's declared version, writing compiler, and name
     * @throws SlangException when the bytes are not a readable serialized module
     */
    public ModuleInfo loadModuleInfoFromIrBlob(byte[] ir) {
        if (ir.length == 0) {
            throw new SlangException("not a serialized module: empty blob", SlangNative.SLANG_FAIL);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment irData = arena.allocate(ir.length);
            MemorySegment.copy(ir, 0, irData, ValueLayout.JAVA_BYTE, 0, ir.length);
            MemorySegment outVersion = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment outCompilerVersion = arena.allocate(ADDRESS);
            MemorySegment outName = arena.allocate(ADDRESS);
            int result = io.github.refux.slang.ffi.gen.SlangAPI.slang_loadModuleInfoFromIRBlob(
                    segment(), irData, ir.length, outVersion, outCompilerVersion, outName);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("ISession::loadModuleInfoFromIRBlob failed", result);
            }
            return new ModuleInfo(
                    outVersion.get(ValueLayout.JAVA_LONG, 0),
                    SlangNative.readUtf8(outCompilerVersion.get(ADDRESS, 0)),
                    SlangNative.readUtf8(outName.get(ADDRESS, 0)));
        }
    }

    /**
     * Creates a type-conformance component that records that {@code type} implements
     * {@code interfaceType}. Composited and linked alongside a program (like any component), it
     * makes the concrete type's witness table available so buffer-sourced existentials of that
     * interface can dispatch to it — the basis of dynamic-dispatch shader composition.
     *
     * @param type the concrete type ({@code slang::TypeReflection*})
     * @param interfaceType the interface it conforms to ({@code slang::TypeReflection*})
     * @param conformanceIdOverride the dispatch id to assign, or {@code -1} to auto-assign
     */
    public ITypeConformance createTypeConformance(
            MemorySegment type, MemorySegment interfaceType, long conformanceIdOverride) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            MemorySegment outDiag = arena.allocate(ADDRESS);
            int result = io.github.refux.slang.ffi.gen.ISession.createTypeConformanceComponentType(
                    segment(), type, interfaceType, out, conformanceIdOverride, outDiag);
            Diagnostics.check("ISession::createTypeConformanceComponentType", result, outDiag);
            return new ITypeConformance(out.get(ADDRESS, 0));
        }
    }

    /**
     * The 16-byte RTTI header identifying {@code type}'s conformance to {@code interfaceType} for
     * dynamic dispatch, to be written at the start of that value's element in a
     * {@code StructuredBuffer<Interface>} (the concrete payload follows it). Slang's layout is: bytes
     * 0-7 an RTTI marker (1 = valid type, 0 = null), bytes 8-11 the sequential dispatch id assigned by
     * {@link #createTypeConformance}, bytes 12-15 unused. Register the conformance first so the id is
     * assigned. Wraps {@code ISession::getDynamicObjectRTTIBytes}.
     *
     * @param type the concrete type ({@code slang::TypeReflection*})
     * @param interfaceType the interface it conforms to ({@code slang::TypeReflection*})
     * @return the 16 header bytes
     */
    public byte[] getDynamicObjectRTTIBytes(MemorySegment type, MemorySegment interfaceType) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(RTTI_HEADER_BYTES);
            int result = io.github.refux.slang.ffi.gen.ISession.getDynamicObjectRTTIBytes(
                    segment(), type, interfaceType, out, RTTI_HEADER_BYTES);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("ISession::getDynamicObjectRTTIBytes failed", result);
            }
            return out.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /** The dynamic-object RTTI header is a fixed 16 bytes (Slang: 8-byte type marker, 4-byte id, 4 unused). */
    private static final int RTTI_HEADER_BYTES = 16;
}

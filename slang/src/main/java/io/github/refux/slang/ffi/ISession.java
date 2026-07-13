package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangCompileException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
}

package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangCompileException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outDiag = arena.allocate(ADDRESS);
            MemorySegment module = io.github.refux.slang.ffi.gen.ISession.loadModuleFromSourceString(
                    segment(),
                    arena.allocateFrom(moduleName),
                    arena.allocateFrom(path),
                    arena.allocateFrom(source),
                    outDiag);
            if (module.address() == 0) {
                String diagnostics = Diagnostics.consume(outDiag);
                throw new SlangCompileException(
                        diagnostics != null ? diagnostics : "module compilation failed", SlangNative.SLANG_FAIL);
            }
            Diagnostics.consume(outDiag); // release a warnings blob if one was produced (M1 drops it)
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
}

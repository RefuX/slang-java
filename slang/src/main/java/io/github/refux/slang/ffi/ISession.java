package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.github.refux.slang.SlangCompileException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * M1 micro-binding of {@code slang::ISession} — module loading and composition (slots from the
 * v2026.13 scan; full 24-slot interface arrives with the M2 generator).
 *
 * <p>Not thread-safe: confine a session and everything loaded through it to one thread at a time
 * (DESIGN.md §11).
 */
public final class ISession extends IUnknown {
    public static final int VT_CREATE_COMPOSITE_COMPONENT_TYPE = 6;
    public static final int VT_LOAD_MODULE_FROM_SOURCE_STRING = 20;

    /**
     * {@code IModule* loadModuleFromSourceString(const char* moduleName, const char* path,
     * const char* string, IBlob** outDiagnostics)}
     */
    private static final MethodHandle MH_LOAD_MODULE_FROM_SOURCE_STRING = SlangNative.LINKER.downcallHandle(
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code SlangResult createCompositeComponentType(IComponentType* const*, SlangInt,
     * IComponentType**, ISlangBlob**)}
     */
    private static final MethodHandle MH_CREATE_COMPOSITE = SlangNative.LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

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
            MemorySegment module = (MemorySegment) MH_LOAD_MODULE_FROM_SOURCE_STRING.invokeExact(
                    fnPtr(VT_LOAD_MODULE_FROM_SOURCE_STRING), segment(),
                    arena.allocateFrom(moduleName), arena.allocateFrom(path),
                    arena.allocateFrom(source), outDiag);
            if (module.address() == 0) {
                String diagnostics = Diagnostics.consume(outDiag);
                throw new SlangCompileException(
                        diagnostics != null ? diagnostics : "module compilation failed", SlangNative.SLANG_FAIL);
            }
            Diagnostics.consume(outDiag); // release a warnings blob if one was produced (M1 drops it)
            return new IModule(module);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
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
            int result = (int) MH_CREATE_COMPOSITE.invokeExact(
                    fnPtr(VT_CREATE_COMPOSITE_COMPONENT_TYPE),
                    segment(),
                    array,
                    (long) components.length,
                    outComposite,
                    outDiag);
            Diagnostics.check("ISession::createCompositeComponentType", result, outDiag);
            return new IComponentType(outComposite.get(ADDRESS, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }
}

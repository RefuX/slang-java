package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.refux.slang.SlangException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * M1 micro-binding of {@code slang::IModule} (vtable = IComponentType's 17 slots + module
 * methods from slot 17 up).
 *
 * <p>Modules are owned by the session that loaded them — wrappers are borrowed (never release),
 * and must not be used after their session is closed.
 */
public final class IModule extends IComponentType {
    public static final int VT_FIND_ENTRY_POINT_BY_NAME = 17;

    /** {@code SlangResult findEntryPointByName(char const* name, IEntryPoint** outEntryPoint)} */
    private static final MethodHandle MH_FIND_ENTRY_POINT_BY_NAME =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    IModule(MemorySegment pointer) {
        super(pointer, false); // borrowed: the session owns its modules
    }

    /**
     * Finds an entry point (a function marked {@code [shader("...")]}) by name, returning a
     * caller-owned wrapper. Throws {@link SlangException} if no such entry point exists.
     */
    public IEntryPoint findEntryPointByName(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int result = (int) MH_FIND_ENTRY_POINT_BY_NAME.invokeExact(
                    fnPtr(VT_FIND_ENTRY_POINT_BY_NAME), segment(), arena.allocateFrom(name), out);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("entry point not found: " + name, result);
            }
            return new IEntryPoint(out.get(ADDRESS, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }
}

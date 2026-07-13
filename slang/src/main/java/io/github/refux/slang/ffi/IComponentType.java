package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * M1 micro-binding of {@code slang::IComponentType} — the linking/code-retrieval subset of its
 * 17-slot vtable (full slot table in DESIGN.md Appendix A; the M2 generator emits the rest).
 */
public class IComponentType extends IUnknown {
    public static final int VT_GET_ENTRY_POINT_CODE = 6;
    public static final int VT_LINK = 10;

    /** {@code SlangResult getEntryPointCode(SlangInt, SlangInt, IBlob**, IBlob**)} */
    private static final MethodHandle MH_GET_ENTRY_POINT_CODE = SlangNative.LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));

    /** {@code SlangResult link(IComponentType** outLinked, ISlangBlob** outDiagnostics)} */
    private static final MethodHandle MH_LINK =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    public IComponentType(MemorySegment pointer) {
        super(pointer);
    }

    protected IComponentType(MemorySegment pointer, boolean owned) {
        super(pointer, owned);
    }

    /**
     * Links this component against its remaining dependencies, returning a new (caller-owned)
     * component type. Throws {@link io.github.refux.slang.SlangCompileException} with the
     * compiler's diagnostics on failure.
     */
    public IComponentType link() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLinked = arena.allocate(ADDRESS);
            MemorySegment outDiag = arena.allocate(ADDRESS);
            int result = (int) MH_LINK.invokeExact(fnPtr(VT_LINK), segment(), outLinked, outDiag);
            Diagnostics.check("IComponentType::link", result, outDiag);
            return new IComponentType(outLinked.get(ADDRESS, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }

    /**
     * Compiles (or fetches) target code for one entry point of a fully linked component.
     *
     * @param entryPointIndex index among the entry points composed into this component
     * @param targetIndex index into the session's {@code SessionDesc.targets} array
     * @return the target code bytes — e.g. a SPIR-V binary, or UTF-8 source for text targets
     */
    public byte[] getEntryPointCode(long entryPointIndex, long targetIndex) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCode = arena.allocate(ADDRESS);
            MemorySegment outDiag = arena.allocate(ADDRESS);
            int result = (int) MH_GET_ENTRY_POINT_CODE.invokeExact(
                    fnPtr(VT_GET_ENTRY_POINT_CODE), segment(), entryPointIndex, targetIndex, outCode, outDiag);
            Diagnostics.check("IComponentType::getEntryPointCode", result, outDiag);
            try (ISlangBlob code = new ISlangBlob(outCode.get(ADDRESS, 0))) {
                return code.toByteArray();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }
}

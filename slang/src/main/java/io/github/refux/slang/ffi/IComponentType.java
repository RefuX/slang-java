package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Wrapper for {@code slang::IComponentType} — the linking/code-retrieval surface. Raw vtable
 * dispatch lives in the generated {@code ffi.gen.IComponentType}.
 */
public class IComponentType extends IUnknown {

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
            int result = io.github.refux.slang.ffi.gen.IComponentType.link(segment(), outLinked, outDiag);
            Diagnostics.check("IComponentType::link", result, outDiag);
            return new IComponentType(outLinked.get(ADDRESS, 0));
        }
    }

    /**
     * Returns the program layout (reflection root) for {@code targetIndex}. The returned pointer
     * is owned by this component — a borrowed reference, valid only while the component lives.
     * Throws {@link SlangException} (with the compiler's diagnostics when present) on failure.
     */
    public MemorySegment getLayout(long targetIndex) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outDiag = arena.allocate(ADDRESS);
            MemorySegment layout =
                    io.github.refux.slang.ffi.gen.IComponentType.getLayout(segment(), targetIndex, outDiag);
            String diagnostics = Diagnostics.consume(outDiag);
            if (layout.address() == 0) {
                throw new SlangException(
                        "IComponentType::getLayout failed" + (diagnostics != null ? ":\n" + diagnostics : ""),
                        SlangNative.SLANG_FAIL);
            }
            return layout;
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
            int result = io.github.refux.slang.ffi.gen.IComponentType.getEntryPointCode(
                    segment(), entryPointIndex, targetIndex, outCode, outDiag);
            Diagnostics.check("IComponentType::getEntryPointCode", result, outDiag);
            try (ISlangBlob code = new ISlangBlob(outCode.get(ADDRESS, 0))) {
                return code.toByteArray();
            }
        }
    }
}

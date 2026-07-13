package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Wrapper for {@code slang::IModule}. Raw vtable dispatch lives in the generated
 * {@code ffi.gen.IModule}.
 *
 * <p>Modules are owned by the session that loaded them — wrappers are borrowed (never release),
 * and must not be used after their session is closed.
 */
public final class IModule extends IComponentType {

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
            int result = io.github.refux.slang.ffi.gen.IModule.findEntryPointByName(
                    segment(), arena.allocateFrom(name), out);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("entry point not found: " + name, result);
            }
            return new IEntryPoint(out.get(ADDRESS, 0));
        }
    }

    /**
     * Serializes this module's checked IR to bytes that {@code ISession.loadModuleFromIrBlob} can
     * reload without re-parsing. The bytes are only readable by a compatible Slang build — key any
     * on-disk cache by the compiler build tag.
     */
    public byte[] serialize() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int result = io.github.refux.slang.ffi.gen.IModule.serialize(segment(), out);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("IModule::serialize failed", result);
            }
            try (ISlangBlob blob = new ISlangBlob(out.get(ADDRESS, 0))) {
                return blob.toByteArray();
            }
        }
    }
}

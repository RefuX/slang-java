package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

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
     * Finds a function by name and checks it as an entry point for {@code stage} (a
     * {@code SlangStage}) — for functions without a {@code [shader("...")]} attribute; one that has
     * it is returned as is. Caller-owned, like {@link #findEntryPointByName}.
     *
     * @throws io.github.refux.slang.SlangCompileException with the compiler's diagnostics when there
     *     is no such function or it is not a valid entry point for the stage
     */
    public IEntryPoint findAndCheckEntryPoint(String name, int stage) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            MemorySegment outDiag = arena.allocate(ADDRESS);
            int result = io.github.refux.slang.ffi.gen.IModule.findAndCheckEntryPoint(
                    segment(), arena.allocateFrom(name), stage, out, outDiag);
            Diagnostics.check("IModule::findAndCheckEntryPoint", result, outDiag);
            return new IEntryPoint(out.get(ADDRESS, 0));
        }
    }

    /**
     * The entry points Slang found when checking this module — functions marked
     * {@code [shader("...")]}, and compute functions marked only {@code [numthreads]} — as
     * caller-owned wrappers.
     */
    public List<IEntryPoint> getDefinedEntryPoints() {
        int count = io.github.refux.slang.ffi.gen.IModule.getDefinedEntryPointCount(segment());
        List<IEntryPoint> entryPoints = new ArrayList<>(count);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            for (int i = 0; i < count; i++) {
                int result = io.github.refux.slang.ffi.gen.IModule.getDefinedEntryPoint(segment(), i, out);
                if (!SlangNative.succeeded(result)) {
                    entryPoints.forEach(IUnknown::close);
                    throw new SlangException("IModule::getDefinedEntryPoint failed", result);
                }
                entryPoints.add(new IEntryPoint(out.get(ADDRESS, 0)));
            }
        }
        return entryPoints;
    }

    /** The module's name — what an {@code import} refers to it by. */
    public String getName() {
        return SlangNative.readUtf8(io.github.refux.slang.ffi.gen.IModule.getName(segment()));
    }

    /** The path the module was loaded from: a found source file, or the synthetic path it was given. */
    public String getFilePath() {
        return SlangNative.readUtf8(io.github.refux.slang.ffi.gen.IModule.getFilePath(segment()));
    }

    /**
     * Every file the module's compilation depended on: its own source, anything it
     * {@code #include}s, and the source files of the modules it {@code import}s.
     */
    public List<String> getDependencyFilePaths() {
        int count = io.github.refux.slang.ffi.gen.IModule.getDependencyFileCount(segment());
        List<String> paths = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            paths.add(SlangNative.readUtf8(io.github.refux.slang.ffi.gen.IModule.getDependencyFilePath(segment(), i)));
        }
        return paths;
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

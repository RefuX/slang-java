package io.github.refux.slang;

import io.github.refux.slang.ffi.IModule;
import java.util.List;

/**
 * A compiled Slang module. Modules are owned by their {@link Session} — this wrapper is
 * borrowed (closing it is a no-op at the native level) and must not be used after the session
 * closes; holding a Module keeps its session reachable.
 */
// Borrows the session-owned native handle via componentHandle() and hands newly-created owned
// handles (entry points) straight to a NativeObject wrapper, so no AutoCloseable is leaked here —
// but the IDE's/Qodana's resource inspection cannot see the borrow/transfer split.
@SuppressWarnings("resource")
public final class Module extends ComponentType {

    Module(Session session, IModule module) {
        super(session, module);
    }

    /**
     * Finds an entry point — a function marked {@code [shader("...")]} — by name.
     *
     * @throws SlangException if the module defines no such entry point
     */
    public EntryPoint entryPoint(String name) {
        session().checkThread();
        return new EntryPoint(session(), module().findEntryPointByName(name));
    }

    /**
     * Finds a function by name and checks it as an entry point for {@code stage} — for functions
     * without a {@code [shader("...")]} attribute (one that has it is returned as is).
     *
     * @throws SlangCompileException with the compiler's diagnostics when there is no such function or
     *     it is not a valid {@code stage} entry point
     */
    public EntryPoint entryPoint(String name, Stage stage) {
        session().checkThread();
        return new EntryPoint(session(), module().findAndCheckEntryPoint(name, Enums.raw(stage, stage.value())));
    }

    /**
     * The entry points Slang found when checking this module, in declaration order: functions marked
     * {@code [shader("...")]}, and compute functions marked only {@code [numthreads]}. Others need
     * {@link #entryPoint(String, Stage)}.
     */
    public List<EntryPoint> entryPoints() {
        session().checkThread();
        return module().getDefinedEntryPoints().stream()
                .map(entryPoint -> new EntryPoint(session(), entryPoint))
                .toList();
    }

    /** The module's name — what an {@code import} refers to it by. */
    public String name() {
        session().checkThread();
        return module().getName();
    }

    /**
     * The path the module was loaded from: the source file {@link Session#loadModule} found, or for
     * {@link Session#loadModuleFromSource} the synthetic {@code <name>.slang}.
     */
    public String filePath() {
        session().checkThread();
        return module().getFilePath();
    }

    /**
     * The files this module was compiled from — its own source, everything it {@code #include}s, and
     * the sources of the modules it {@code import}s — which is what a build system or hot-reload
     * watcher should watch to know when to recompile it.
     */
    public List<String> dependencyFiles() {
        session().checkThread();
        return List.copyOf(module().getDependencyFilePaths());
    }

    /**
     * Serializes this module's checked IR so it can be reloaded via
     * {@link Session#loadModuleFromIr(String, byte[])} without re-parsing. The bytes are only
     * readable by a compatible Slang build, so key any on-disk cache by
     * {@link GlobalSession#buildTagString()}.
     */
    public byte[] serialize() {
        session().checkThread();
        return module().serialize();
    }

    private IModule module() {
        return (IModule) componentHandle();
    }
}

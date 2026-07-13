package io.github.refux.slang;

import io.github.refux.slang.ffi.IModule;

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
        return new EntryPoint(session(), ((IModule) componentHandle()).findEntryPointByName(name));
    }

    /**
     * Serializes this module's checked IR so it can be reloaded via
     * {@link Session#loadModuleFromIr(String, byte[])} without re-parsing. The bytes are only
     * readable by a compatible Slang build, so key any on-disk cache by
     * {@link GlobalSession#buildTagString()}.
     */
    public byte[] serialize() {
        session().checkThread();
        return ((IModule) componentHandle()).serialize();
    }
}

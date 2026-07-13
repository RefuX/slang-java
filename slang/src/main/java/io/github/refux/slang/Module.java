package io.github.refux.slang;

import io.github.refux.slang.ffi.IModule;

/**
 * A compiled Slang module. Modules are owned by their {@link Session} — this wrapper is
 * borrowed (closing it is a no-op at the native level) and must not be used after the session
 * closes; holding a Module keeps its session reachable.
 */
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
}

package io.github.refux.slang;

import io.github.refux.slang.ffi.IGlobalSession;

/**
 * Entry point of the idiomatic Slang API:
 *
 * {@snippet :
 * try (GlobalSession global = Slang.createGlobalSession();
 *      Session session = global.newSession().target(CompileTarget.SPIRV).create()) {
 *     Module module = session.loadModuleFromSource("hello", source);
 *     try (ComponentType linked = session.composite(module, module.entryPoint("main")).link()) {
 *         byte[] spirv = linked.entryPointCode(0, 0);
 *     }
 * }
 * }
 *
 * <p>The native library is located per the loader's resolution order (see
 * {@link io.github.refux.slang.loader.SlangLibrary}).
 */
public final class Slang {
    private Slang() {}

    /**
     * Creates the process-level compiler session. Expensive (loads Slang's core module) —
     * create once and share; sessions created from it are the cheap per-compilation scope.
     */
    public static GlobalSession createGlobalSession() {
        return new GlobalSession(IGlobalSession.create());
    }
}

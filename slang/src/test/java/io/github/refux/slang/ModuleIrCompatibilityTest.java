package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Covers loading serialized module IR across Slang versions.
 *
 * <p>The case that matters: Slang <em>aborts the process</em> when {@code loadModuleFromIRBlob} is
 * handed IR whose module version it does not read — no exception, no diagnostic, no {@code hs_err}.
 * So {@link Session#loadModuleFromIr} must decide compatibility before the bytes reach native code.
 * Without that, any consumer accepting IR built elsewhere (a plug-in, a cache written by another
 * toolchain) can be killed outright by a stale artifact.
 *
 * <p>{@code fixtures/legacy_probe.slang-module} is a trivial module serialized by Slang 2026.8
 * (module version 15), checked in so the mismatch is reproducible without installing an old
 * compiler. Tests needing a mismatch skip when run against a build that reads version 15.
 */
class ModuleIrCompatibilityTest {

    private static final String LEGACY_FIXTURE = "fixtures/legacy_probe.slang-module";

    private static final String PROBE_SOURCE = """
            module round_trip_probe;
            public int probe() { return 1; }
            """;

    private static byte[] legacyIr() throws IOException {
        try (InputStream in = ModuleIrCompatibilityTest.class.getClassLoader().getResourceAsStream(LEGACY_FIXTURE)) {
            assertNotNull(in, "missing test fixture: " + LEGACY_FIXTURE);
            return in.readAllBytes();
        }
    }

    private static Session spirvSession(GlobalSession global) {
        return global.newSession().target(CompileTarget.SPIRV).create();
    }

    /** The version query must survive IR the loader would abort on — that is the whole point of it. */
    @Test
    void moduleInfoReadsForeignIrWithoutLoadingIt() throws IOException {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            ModuleInfo info = session.moduleInfo(legacyIr());

            assertEquals("legacy_probe", info.name());
            assertEquals("2026.8", info.compilerVersion(), "fixture was built by slangc 2026.8");
            assertEquals(15, info.moduleVersion(), "Slang 2026.8 emits module version 15");
        }
    }

    /** A build always reads what it writes, so the observed version is a sound load predicate. */
    @Test
    void supportedModuleVersionMatchesWhatThisBuildSerializes() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            byte[] ir = session.loadModuleFromSource("round_trip_probe", PROBE_SOURCE)
                    .serialize();

            assertTrue(global.supportedModuleVersion() > 0, "a real module version was observed");
            assertEquals(
                    global.supportedModuleVersion(),
                    session.moduleInfo(ir).moduleVersion(),
                    "supportedModuleVersion is the version this build serializes to");
        }
    }

    /** Self-built IR must still load — the guard must not reject the compatible case. */
    @Test
    void selfSerializedIrStillLoads() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            byte[] ir = session.loadModuleFromSource("round_trip_probe", PROBE_SOURCE)
                    .serialize();

            assertNotNull(session.loadModuleFromIr("reloaded", ir));
        }
    }

    /**
     * The regression: before the pre-check this call took the JVM down with it, so a failure here
     * is a dead test JVM rather than a red test.
     */
    @Test
    void incompatibleIrThrowsInsteadOfAbortingTheProcess() throws IOException {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            byte[] legacy = legacyIr();
            assumeTrue(
                    global.supportedModuleVersion()
                            != session.moduleInfo(legacy).moduleVersion(),
                    "this Slang build reads the fixture's module version, so there is no mismatch to test");

            SlangCompileException thrown =
                    assertThrows(SlangCompileException.class, () -> session.loadModuleFromIr("legacy_probe", legacy));

            // The message has to be actionable: which module, which versions, and what to do.
            assertTrue(thrown.getMessage().contains("legacy_probe"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("2026.8"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Recompile it from source"), thrown.getMessage());
        }
    }

    /** Garbage must fail as an exception too, not as an abort. */
    @Test
    void nonModuleBytesThrow() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            assertThrows(SlangException.class, () -> session.moduleInfo(new byte[0]));
            assertThrows(SlangException.class, () -> session.moduleInfo("not a slang module".getBytes()));
        }
    }
}

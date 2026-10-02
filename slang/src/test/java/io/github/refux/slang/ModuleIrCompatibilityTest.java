package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.refux.slang.ffi.SlangNative;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Covers loading serialized module IR across Slang versions.
 *
 * <p>The case that matters: Slang releases before 2026.18.3 <em>abort the process</em> when
 * {@code loadModuleFromIRBlob} is handed IR whose module version they do not read — no exception,
 * no diagnostic, no {@code hs_err}. So {@link Session#loadModuleFromIr} decides compatibility before
 * the bytes reach native code. Without that, any consumer accepting IR built elsewhere (a plug-in,
 * a cache written by another toolchain) can be killed outright by a stale artifact. Newer releases
 * report the mismatch instead, and the same check keeps the failure one actionable exception.
 *
 * <p>{@code fixtures/legacy_probe.slang-module} is a trivial module serialized by Slang 2026.8
 * (module version 15, serialization format 1), checked in so a stale artifact is reproducible
 * without installing an old compiler. Slang 2026.18.3 retired format 1 altogether, so against the
 * pinned release it is the retired-format case: not even its metadata is readable.
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

    /** The version query reads what readable IR declares about itself, without loading it. */
    @Test
    void moduleInfoReadsWhatSerializedIrDeclares() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            byte[] ir = session.loadModuleFromSource("round_trip_probe", PROBE_SOURCE)
                    .serialize();

            ModuleInfo info = session.moduleInfo(ir);

            assertEquals("round_trip_probe", info.name());
            assertNotNull(info.compilerVersion());
            assertFalse(info.compilerVersion().isBlank(), "the writing compiler is recorded");
        }
    }

    /**
     * A retired serialization format must fail as a distinct, catchable result — the one
     * {@link Session#loadModuleFromIr} turns into its actionable message — never as an abort.
     */
    @Test
    void moduleInfoRefusesARetiredSerializationFormat() throws IOException {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            byte[] legacy = legacyIr();

            SlangException thrown = assertThrows(SlangException.class, () -> session.moduleInfo(legacy));

            assertEquals(SlangNative.SLANG_E_NOT_AVAILABLE, thrown.result(), thrown.getMessage());
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

            SlangCompileException thrown =
                    assertThrows(SlangCompileException.class, () -> session.loadModuleFromIr("legacy_probe", legacy));

            // The message has to be actionable: which module, and what to do.
            assertTrue(thrown.getMessage().contains("legacy_probe"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Recompile it from source"), thrown.getMessage());
        }
    }

    /** Garbage must fail as an exception too, not as an abort — and not pass for a retired format. */
    @Test
    void nonModuleBytesThrow() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            assertThrows(SlangException.class, () -> session.moduleInfo(new byte[0]));
            SlangException garbage =
                    assertThrows(SlangException.class, () -> session.moduleInfo("not a slang module".getBytes()));
            assertNotEquals(SlangNative.SLANG_E_NOT_AVAILABLE, garbage.result());
        }
    }
}

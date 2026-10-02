package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.refux.slang.ffi.SlangNative;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 *
 * <p>The cross-version cases are forged instead: no released Slang writes format 2 at any module
 * version but 32, so {@link #withModuleVersion} relabels self-serialized IR. That is enough for
 * what is under test — Slang decides whether to load from the version field, before reading the
 * module itself.
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

    /**
     * {@code ir} relabelled as module {@code version}. The serialized metadata holds the version as
     * one little-endian 64-bit field; it is found by patching each 8-byte run that holds the
     * current version and asking {@link Session#moduleInfo} which patch it reports back.
     */
    private static byte[] withModuleVersion(Session session, byte[] ir, long version) {
        long current = session.moduleInfo(ir).moduleVersion();
        ByteBuffer original = ByteBuffer.wrap(ir).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; at + Long.BYTES <= ir.length; at++) {
            if (original.getLong(at) != current) {
                continue;
            }
            byte[] patched = ir.clone();
            ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putLong(at, version);
            try {
                if (session.moduleInfo(patched).moduleVersion() == version) {
                    return patched;
                }
            } catch (SlangException notTheVersionField) {
                // These 8 bytes held something else; keep looking.
            }
        }
        return fail("no 64-bit module version field in serialized IR: the metadata layout changed, so "
                + "withModuleVersion needs another way to forge a version");
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
     * Which versions load is Slang's call, not the binding's: a version older than the one this
     * build writes loads when Slang still reads it. Against the pinned 2026.19, which writes 32 and
     * reads 31 through 32, that is version 31 — which the exact-version check refused before.
     */
    @Test
    void olderModuleVersionSlangStillReadsLoads() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            byte[] ir = session.loadModuleFromSource("round_trip_probe", PROBE_SOURCE)
                    .serialize();
            byte[] older = withModuleVersion(session, ir, global.supportedModuleVersion() - 1);

            assertNotNull(session.loadModuleFromIr("older", older));
        }
    }

    /**
     * A version Slang does not read — newer than this build, or older than its range — is refused
     * as an exception that says what to do, never an abort.
     */
    @Test
    void moduleVersionSlangDoesNotReadThrows() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = spirvSession(global)) {
            byte[] ir = session.loadModuleFromSource("round_trip_probe", PROBE_SOURCE)
                    .serialize();

            for (long version : new long[] {global.supportedModuleVersion() + 1, 1}) {
                byte[] forged = withModuleVersion(session, ir, version);

                SlangCompileException thrown =
                        assertThrows(SlangCompileException.class, () -> session.loadModuleFromIr("forged", forged));

                assertTrue(thrown.getMessage().contains("round_trip_probe"), thrown.getMessage());
                assertTrue(thrown.getMessage().contains("module version " + version), thrown.getMessage());
                assertTrue(thrown.getMessage().contains("Recompile it from source"), thrown.getMessage());
            }
        }
    }

    /**
     * Deferring to Slang is only safe where Slang reports what it cannot read, which the build tag
     * decides; anything that is not a release number must fall back to the exact-version check.
     */
    @Test
    void onlyTrustsReleasesThatReportUnreadableIr() {
        for (String tag : new String[] {"2026.18.3", "2026.19", "v2026.19", "2026.19-12-gdeadbee", "2027.1"}) {
            assertTrue(GlobalSession.releasedAtLeast(tag, 2026, 18, 3), tag);
        }
        for (String tag : new String[] {"2026.18.2", "2026.18", "2026.9", "2025.30", "0.0.0-unknown", "unknown", ""}) {
            assertFalse(GlobalSession.releasedAtLeast(tag, 2026, 18, 3), tag);
        }
        assertFalse(GlobalSession.releasedAtLeast(null, 2026, 18, 3));

        try (GlobalSession global = Slang.createGlobalSession()) {
            assertTrue(global.validatesModuleIrOnLoad(), "the pinned release reports unreadable IR");
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

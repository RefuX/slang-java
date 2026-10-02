package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.refux.slang.ffi.gen.CompilerOptionName;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Compiler options reach Slang — each typed setter checked by an effect it has on the output. */
class CompilerOptionsTest {

    private static final String COMPUTE = """
            RWStructuredBuffer<float> output;
            float scaled(float x) { float y = x * 2.0; float z = y + 0.0; return z * 1.0; }
            [shader("compute")] [numthreads(1,1,1)]
            void main(uint3 tid : SV_DispatchThreadID)
            {
                float sum = 0;
                for (int i = 0; i < 4; i++) sum += scaled(float(i));
                output[0] = sum;
            }
            """;

    /** Warns with E30081 (implicit float-to-int conversion) and nothing else. */
    private static final String WARNS = """
            [shader("compute")] [numthreads(1,1,1)]
            void main(uint3 tid : SV_DispatchThreadID) { int truncated = 1.5; }
            """;

    private static byte[] compile(
            GlobalSession global, CompileTarget target, String source, UnaryOperator<SessionBuilder> configure) {
        try (Session session =
                configure.apply(global.newSession().target(target)).create()) {
            Module module = session.loadModuleFromSource("options_probe", source);
            try (ComponentType linked =
                    session.composite(module, module.entryPoint("main")).link()) {
                return linked.entryPointCode(0, 0);
            }
        }
    }

    private static boolean hasDebugInfo(byte[] spirv) {
        return new String(spirv, StandardCharsets.ISO_8859_1).contains("NonSemantic.Shader.DebugInfo");
    }

    @Test
    void debugInfoEmitsSourceLevelDebugInformation() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertFalse(
                    hasDebugInfo(compile(global, CompileTarget.SPIRV, COMPUTE, s -> s.debugInfo(DebugInfoLevel.NONE))));
            assertTrue(hasDebugInfo(
                    compile(global, CompileTarget.SPIRV, COMPUTE, s -> s.debugInfo(DebugInfoLevel.STANDARD))));
        }
    }

    @Test
    void optimizationShrinksTheCode() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            int unoptimized =
                    compile(global, CompileTarget.SPIRV, COMPUTE, s -> s.optimization(OptimizationLevel.NONE)).length;
            int maximal = compile(global, CompileTarget.SPIRV, COMPUTE, s -> s.optimization(OptimizationLevel.MAXIMAL))
                    .length;
            assertTrue(maximal < unoptimized, "MAXIMAL (" + maximal + " bytes) vs NONE (" + unoptimized + " bytes)");
        }
    }

    @Test
    void warningsAsErrorsFailsTheLoad() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            List<String> warnings = new ArrayList<>();
            compile(global, CompileTarget.SPIRV, WARNS, s -> s.onDiagnostics(warnings::add));
            assertTrue(String.join("", warnings).contains("30081"), "the probe source warns: " + warnings);

            assertThrows(
                    SlangCompileException.class,
                    () -> compile(global, CompileTarget.SPIRV, WARNS, SessionBuilder::warningsAsErrors));
            assertThrows(
                    SlangCompileException.class,
                    () -> compile(global, CompileTarget.SPIRV, WARNS, s -> s.warningsAsErrors("30081")));
        }
    }

    @Test
    void disableWarningsSilencesThem() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            List<String> warnings = new ArrayList<>();
            compile(
                    global,
                    CompileTarget.SPIRV,
                    WARNS,
                    s -> s.onDiagnostics(warnings::add).disableWarnings("30081"));
            assertTrue(warnings.isEmpty(), "disabled warning still reported: " + warnings);
        }
    }

    @Test
    void capabilitiesResolveByName() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertTrue(compile(global, CompileTarget.SPIRV, COMPUTE, s -> s.capability("spirv_1_5")).length > 0);
            assertThrows(
                    IllegalArgumentException.class, () -> global.newSession().capability("not_a_capability"));
        }
    }

    /** MSVC packing starts new storage when the underlying type size changes; Slang's default shares it. */
    @Test
    void bitfieldPackingChangesTheStorageLayout() {
        String source = """
                struct Packed { uint8_t a : 4; uint16_t b : 4; };
                RWStructuredBuffer<Packed> output;
                [shader("compute")] [numthreads(1,1,1)]
                void main(uint3 tid : SV_DispatchThreadID) { Packed p; p.a = 1; p.b = 2; output[0] = p; }
                """;
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertFalse(declaresByteBacking(
                    compile(global, CompileTarget.HLSL, source, s -> s.bitfieldPacking(BitfieldPacking.DEFAULT))));
            assertTrue(declaresByteBacking(
                    compile(global, CompileTarget.HLSL, source, s -> s.bitfieldPacking(BitfieldPacking.MSVC))));
        }
    }

    private static boolean declaresByteBacking(byte[] hlsl) {
        return new String(hlsl, StandardCharsets.UTF_8)
                .lines()
                .anyMatch(line -> line.contains("uint8_t") && line.contains("bit_field_backing"));
    }

    @Test
    void languageVersionIsAccepted() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertTrue(compile(global, CompileTarget.SPIRV, COMPUTE, s -> s.languageVersion(2026)).length > 0);
        }
    }

    /** The raw escape hatch, at session level and per target (where a target's entries land on its own desc). */
    @Test
    void rawOptionsReachTheSessionAndEachTarget() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertThrows(
                    SlangCompileException.class,
                    () -> compile(
                            global,
                            CompileTarget.SPIRV,
                            WARNS,
                            s -> s.option(CompilerOptionName.WarningsAsErrors, "all")));

            try (Session session = global.newSession()
                    .target(CompileTarget.SPIRV)
                    .target(
                            CompileTarget.SPIRV,
                            t -> t.option(CompilerOptionName.DebugInformation, DebugInfoLevel.STANDARD.value()))
                    .create()) {
                Module module = session.loadModuleFromSource("per_target", COMPUTE);
                try (ComponentType linked =
                        session.composite(module, module.entryPoint("main")).link()) {
                    assertFalse(hasDebugInfo(linked.entryPointCode(0, 0)), "target 0 has no debug option");
                    assertTrue(hasDebugInfo(linked.entryPointCode(0, 1)), "target 1 asked for debug info");
                }
            }
        }
    }

    @Test
    void unknownSentinelsAreRefused() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            SessionBuilder builder = global.newSession();
            assertThrows(IllegalArgumentException.class, () -> builder.optimization(OptimizationLevel.UNKNOWN));
            assertThrows(IllegalArgumentException.class, () -> builder.debugInfo(DebugInfoLevel.UNKNOWN));
            assertThrows(IllegalArgumentException.class, () -> builder.bitfieldPacking(BitfieldPacking.UNKNOWN));
        }
    }
}

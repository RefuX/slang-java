package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M3 exit criterion (DESIGN.md §16): the design document's §8 sample compiles and runs as
 * written, plus coverage for the builder, warnings consumer, reflection snapshot, and
 * lifecycle rules.
 */
class IdiomaticApiTest {

    /** DESIGN.md §8, verbatim apart from the surrounding assertions. */
    @Test
    void designDocSampleRunsAsWritten() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.SPIRV, t -> t.profile("spirv_1_5"))
                        .searchPath(Path.of("shaders"))
                        .define("USE_FOG", "1")
                        .create()) {

            Module module = session.loadModuleFromSource("hello", """
                    [shader("compute")] [numthreads(8,8,1)]
                    void main(uint3 tid : SV_DispatchThreadID) { }
                    """);

            try (ComponentType linked =
                    session.composite(module, module.entryPoint("main")).link()) {
                byte[] spirv = linked.entryPointCode(0, 0);
                ShaderReflection refl = linked.layout(0);
                for (var param : refl.parameters()) {
                    System.out.println(param.name() + " : " + param.category());
                }

                assertEquals(
                        0x0723_0203,
                        ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(0),
                        "SPIR-V magic");
                assertTrue(refl.parameters().isEmpty(), "this shader declares no global parameters");
            }
        }
    }

    @Test
    void reflectionSnapshotSeesBufferParameter() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession().target(CompileTarget.HLSL).create()) {
            Module module = session.loadModuleFromSource("reflected", """
                    RWStructuredBuffer<float> result;

                    [shader("compute")] [numthreads(4,1,1)]
                    void computeMain(uint3 tid : SV_DispatchThreadID)
                    {
                        result[tid.x] = float(tid.x);
                    }
                    """);
            try (ComponentType linked =
                    session.composite(module, module.entryPoint("computeMain")).link()) {
                List<ShaderReflection.Parameter> parameters = linked.layout(0).parameters();
                assertEquals(1, parameters.size());
                assertEquals("result", parameters.get(0).name());
                assertEquals(
                        ParameterCategory.UNORDERED_ACCESS,
                        parameters.get(0).category(),
                        "an RWStructuredBuffer binds as UAV on the HLSL target");
            }
        }
    }

    @Test
    void multiTargetBuilderMapsTargetIndicesInOrder() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.SPIRV, t -> t.profile("spirv_1_5"))
                        .target(CompileTarget.HLSL)
                        .create()) {
            Module module = session.loadModuleFromSource("multi", """
                    [shader("compute")] [numthreads(1,1,1)]
                    void main() { }
                    """);
            try (ComponentType linked =
                    session.composite(module, module.entryPoint("main")).link()) {
                assertEquals(
                        0x0723_0203,
                        ByteBuffer.wrap(linked.entryPointCode(0, 0))
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .getInt(0));
                String hlsl = new String(linked.entryPointCode(0, 1), StandardCharsets.UTF_8);
                assertTrue(hlsl.contains("main"));
            }
        }
    }

    @Test
    void warningsReachTheDiagnosticsConsumer() {
        List<String> diagnostics = new ArrayList<>();
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.HLSL)
                        .onDiagnostics(diagnostics::add)
                        .create()) {
            session.loadModuleFromSource("warny", """
                    [deprecated("use newFunc instead")]
                    float oldFunc(float x) { return x; }

                    [shader("compute")] [numthreads(1,1,1)]
                    void main()
                    {
                        oldFunc(1.0);
                    }
                    """);
        }
        assertFalse(diagnostics.isEmpty(), "the deprecated call should produce a warning");
        assertTrue(
                diagnostics.get(0).contains("warning"),
                "diagnostics text should be the compiler's warning output: " + diagnostics);
    }

    @Test
    void builderRejectsMisconfiguration() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertThrows(IllegalStateException.class, () -> global.newSession().create(), "no targets");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> global.newSession()
                            .target(CompileTarget.SPIRV, t -> t.profile("not_a_profile"))
                            .create(),
                    "unknown profile name");
        }
    }

    @Test
    void closedObjectsFailFastAndCloseIsIdempotent() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            Session session = global.newSession().target(CompileTarget.HLSL).create();
            session.close();
            session.close(); // idempotent
            assertTrue(session.isClosed());
            assertThrows(IllegalStateException.class, () -> session.loadModuleFromSource("late", "void f() {}"));
        }
    }

    @Test
    void compileErrorsCarryDiagnostics() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session =
                        global.newSession().target(CompileTarget.SPIRV).create()) {
            SlangCompileException e =
                    assertThrows(SlangCompileException.class, () -> session.loadModuleFromSource("broken", """
                            void main() { NotAType x; }
                            """));
            assertTrue(e.getMessage().contains("error"));
            assertTrue(e.getMessage().contains("broken.slang"));
        }
    }
}

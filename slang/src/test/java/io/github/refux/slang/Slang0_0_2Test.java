package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Capabilities added in 0.0.2 for the Kotu migration (docs SlangJavaMigrationDesign §2.2):
 * module IR serialize/round-trip, reflection find/dump, and type-conformance dynamic dispatch.
 */
class Slang0_0_2Test {

    static final int SPIRV_MAGIC = 0x0723_0203;

    /** serialize() → loadModuleFromIr() round-trips, and the reloaded module still compiles. */
    @Test
    void serializedModuleReloadsFromIrAndCompiles() {
        String helper = """
                public float twice(float x) { return 2.0 * x; }
                """;
        try (GlobalSession global = Slang.createGlobalSession()) {
            byte[] ir;
            try (Session session =
                    global.newSession().target(CompileTarget.SPIRV).create()) {
                ir = session.loadModuleFromSource("helper", helper).serialize();
            }
            assertTrue(ir.length > 0, "serialized IR is non-empty");

            // A fresh session reloads the module from IR (no re-parse) and compiles a user of it.
            try (Session session = global.newSession()
                    .target(CompileTarget.SPIRV, t -> t.profile("spirv_1_5"))
                    .create()) {
                session.loadModuleFromIr("helper", ir);
                Module main = session.loadModuleFromSource("main", """
                        import helper;
                        RWStructuredBuffer<float> result;
                        [shader("compute")] [numthreads(1,1,1)]
                        void main(uint3 tid : SV_DispatchThreadID) { result[tid.x] = twice(21.0); }
                        """);
                try (ComponentType linked =
                        session.composite(main, main.entryPoint("main")).link()) {
                    byte[] spirv = linked.entryPointCode(0, 0);
                    assertEquals(
                            SPIRV_MAGIC,
                            ByteBuffer.wrap(spirv)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .getInt(0));
                }
            }
        }
    }

    /** Two serializations of the same source are byte-identical (deterministic IR). */
    @Test
    void serializationIsDeterministic() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session a = global.newSession().target(CompileTarget.SPIRV).create();
                Session b = global.newSession().target(CompileTarget.SPIRV).create()) {
            byte[] ir1 = a.loadModuleFromSource("m", "public float f() { return 1.0; }")
                    .serialize();
            byte[] ir2 = b.loadModuleFromSource("m", "public float f() { return 1.0; }")
                    .serialize();
            assertArrayEquals(ir1, ir2);
        }
    }

    /** findTypeByName / findParameter / dump over a linked program. */
    @Test
    void reflectionFindHelpersWork() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession().target(CompileTarget.HLSL).create()) {
            Module module = session.loadModuleFromSource("r", """
                    struct Material { float4 tint; }
                    RWStructuredBuffer<float> result;
                    [shader("compute")] [numthreads(1,1,1)]
                    void main() { result[0] = 1.0; }
                    """);
            try (ComponentType linked =
                    session.composite(module, module.entryPoint("main")).link()) {
                ShaderReflection reflection = linked.layout(0);
                assertNotNull(reflection.findTypeByName("Material"), "findTypeByName sees the struct");
                assertNotNull(reflection.findParameter("result"), "findParameter finds the global buffer");
                assertEquals(null, reflection.findParameter("nope"));
                assertTrue(reflection.dump().contains("result"), "dump mentions the parameter");
            }
        }
    }

    /**
     * Dynamic dispatch (§22.4): a shader iterates existentials from a StructuredBuffer<IModifier>
     * and dispatches gModifiers[i].apply(...). Compiling it requires the concrete types' witness
     * tables — registered here via type conformances — otherwise Slang fails with E50100
     * "no type conformances found". This is the exact pattern Kotu's new composer uses.
     */
    @Test
    void typeConformancesEnableExistentialDispatch() {
        String modifiers = """
                public interface IModifier { float apply(float x); }
                public struct Doubler : IModifier { public float apply(float x) { return x * 2.0; } }
                public struct Negator : IModifier { public float apply(float x) { return -x; } }
                """;
        String shader = """
                import modifiers;
                RWStructuredBuffer<float> result;
                StructuredBuffer<IModifier> gModifiers;
                [shader("compute")] [numthreads(1,1,1)]
                void main(uint3 tid : SV_DispatchThreadID) {
                    float v = 1.0;
                    for (uint i = 0; i < 2; ++i) { v = gModifiers[i].apply(v); }
                    result[tid.x] = v;
                }
                """;
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.SPIRV, t -> t.profile("spirv_1_5"))
                        .create()) {
            Module modifierModule = session.loadModuleFromSource("modifiers", modifiers);
            Module shaderModule = session.loadModuleFromSource("shader", shader);

            // Type reflection for the concrete types + the interface, from the module's layout.
            ShaderReflection modRefl = modifierModule.layout(0);
            TypeReflection iface = modRefl.findTypeByName("IModifier");
            TypeReflection doubler = modRefl.findTypeByName("Doubler");
            TypeReflection negator = modRefl.findTypeByName("Negator");
            assertNotNull(iface);
            assertNotNull(doubler);
            assertNotNull(negator);

            try (TypeConformance c0 = session.createTypeConformance(doubler, iface, 0);
                    TypeConformance c1 = session.createTypeConformance(negator, iface, 1);
                    ComponentType linked = session.composite(shaderModule, shaderModule.entryPoint("main"), c0, c1)
                            .link()) {
                byte[] spirv = linked.entryPointCode(0, 0);
                assertEquals(
                        SPIRV_MAGIC,
                        ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(0),
                        "existential-dispatch shader compiled with the conformances registered");
                assertTrue(spirv.length > 100, "non-trivial SPIR-V with witness tables");
            }
        }
    }
}

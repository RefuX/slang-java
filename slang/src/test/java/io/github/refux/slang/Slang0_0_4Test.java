package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Covers the slang-java 0.0.4 addition: {@link Session#getDynamicObjectRTTIBytes}, the CPU's source of
 * truth for the 16-byte header written at the start of each element of a
 * {@code StructuredBuffer<Interface>} in dynamic-dispatch shaders.
 */
class Slang0_0_4Test {

    private static final String MODIFIERS = """
            public interface IModifier { float apply(float x); }
            public struct Doubler : IModifier { public float apply(float x) { return x * 2.0; } }
            public struct Negator : IModifier { public float apply(float x) { return -x; } }
            """;

    private static final String DISPATCH_SHADER = """
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

    /**
     * The header is a fixed 16 bytes: byte 0-3 is Slang's valid-type marker (1) and byte 8-11 is the
     * conformance id. {@link Session#getDynamicObjectRTTIBytes} returns the **registration-order**
     * (sequential) id, while the shader's dispatch {@code OpSwitch} branches on the
     * {@code conformanceIdOverride} — so they agree only when the override equals the registration
     * order, which is how a caller must register (id 0 first, id 1 next, …). Here the ids match the
     * order, so the header id is an OpSwitch case and a CPU writing this header dispatches correctly.
     */
    @Test
    void headerCarriesTheMarkerAndTheIdTheDispatchSwitchUses() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.SPIRV_ASM, t -> t.profile("spirv_1_5"))
                        .create()) {
            Module modifiers = session.loadModuleFromSource("modifiers", MODIFIERS);
            Module shader = session.loadModuleFromSource("shader", DISPATCH_SHADER);
            ShaderReflection refl = modifiers.layout(0);
            TypeReflection iface = refl.findTypeByName("IModifier");
            TypeReflection doubler = refl.findTypeByName("Doubler");
            TypeReflection negator = refl.findTypeByName("Negator");
            assertNotNull(iface);
            assertNotNull(doubler);
            assertNotNull(negator);

            // Register in id order (0 then 1), the only regime in which the header id matches the switch.
            try (TypeConformance c0 = session.createTypeConformance(doubler, iface, 0);
                    TypeConformance c1 = session.createTypeConformance(negator, iface, 1);
                    ComponentType linked = session.composite(shader, shader.entryPoint("main"), c0, c1)
                            .link()) {
                byte[] doublerHeader = session.getDynamicObjectRTTIBytes(doubler, iface);
                byte[] negatorHeader = session.getDynamicObjectRTTIBytes(negator, iface);

                assertEquals(16, doublerHeader.length, "RTTI header is 16 bytes");
                assertEquals(1, intAt(doublerHeader, 0), "byte 0-3 is the valid-type marker");
                assertEquals(0, intAt(doublerHeader, 8), "Doubler's id (0) sits at offset 8");
                assertEquals(1, intAt(negatorHeader, 0), "byte 0-3 is the valid-type marker");
                assertEquals(1, intAt(negatorHeader, 8), "Negator's id (1) sits at offset 8");

                String asm = new String(linked.entryPointCode(0, 0), StandardCharsets.UTF_8);
                Set<Integer> switchCases = opSwitchCaseLiterals(asm);
                assertTrue(switchCases.contains(0), "header id 0 is an OpSwitch case; cases=" + switchCases);
                assertTrue(switchCases.contains(1), "header id 1 is an OpSwitch case; cases=" + switchCases);
            }
        }
    }

    /**
     * Mimics the sprite composer: two stages on different interfaces, compiled separately in one
     * session, each registering its own modifiers from id 0. If the sequential id were session-global
     * rather than per-interface, stage B's header id (from getDynamicObjectRTTIBytes) would drift off
     * the per-stage id its OpSwitch branches on. This proves it does not.
     */
    @Test
    void headerIdIsPerInterfaceAcrossSeparateCompilesInOneSession() {
        String stageA = """
                public interface IA { float apply(float x); }
                public struct A0 : IA { public float apply(float x) { return x + 1.0; } }
                public struct A1 : IA { public float apply(float x) { return x + 2.0; } }
                """;
        String stageB = """
                public interface IB { float apply(float x); }
                public struct B0 : IB { public float apply(float x) { return x * 3.0; } }
                public struct B1 : IB { public float apply(float x) { return x * 4.0; } }
                """;
        String shaderA = """
                import a;
                RWStructuredBuffer<float> r;
                StructuredBuffer<IA> g;
                [shader("compute")] [numthreads(1,1,1)]
                void main(uint3 t : SV_DispatchThreadID) { float v = 1.0; for (uint i = 0; i < 2; ++i) v = g[i].apply(v); r[t.x] = v; }
                """;
        String shaderB = """
                import b;
                RWStructuredBuffer<float> r;
                StructuredBuffer<IB> g;
                [shader("compute")] [numthreads(1,1,1)]
                void main(uint3 t : SV_DispatchThreadID) { float v = 1.0; for (uint i = 0; i < 2; ++i) v = g[i].apply(v); r[t.x] = v; }
                """;
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.SPIRV_ASM, t -> t.profile("spirv_1_5"))
                        .create()) {
            Module a = session.loadModuleFromSource("a", stageA);
            Module b = session.loadModuleFromSource("b", stageB);
            Module sa = session.loadModuleFromSource("sa", shaderA);
            Module sb = session.loadModuleFromSource("sb", shaderB);
            ShaderReflection ra = a.layout(0);
            ShaderReflection rb = b.layout(0);
            TypeReflection ia = ra.findTypeByName("IA");
            TypeReflection ib = rb.findTypeByName("IB");

            // Stage A first: two conformances (ids 0, 1) — this advances any session-global counter.
            try (TypeConformance ca0 = session.createTypeConformance(ra.findTypeByName("A0"), ia, 0);
                    TypeConformance ca1 = session.createTypeConformance(ra.findTypeByName("A1"), ia, 1);
                    ComponentType la = session.composite(sa, sa.entryPoint("main"), ca0, ca1)
                            .link()) {
                la.entryPointCode(0, 0);
            }

            // Stage B in the same session: its modifiers registered from id 0 again (per-interface).
            try (TypeConformance cb0 = session.createTypeConformance(rb.findTypeByName("B0"), ib, 0);
                    TypeConformance cb1 = session.createTypeConformance(rb.findTypeByName("B1"), ib, 1);
                    ComponentType lb = session.composite(sb, sb.entryPoint("main"), cb0, cb1)
                            .link()) {
                int b0Id = intAt(session.getDynamicObjectRTTIBytes(rb.findTypeByName("B0"), ib), 8);
                Set<Integer> switchCasesB =
                        opSwitchCaseLiterals(new String(lb.entryPointCode(0, 0), StandardCharsets.UTF_8));
                assertEquals(0, b0Id, "stage B's first modifier is id 0 (per-interface, not session-global)");
                assertTrue(
                        switchCasesB.contains(b0Id),
                        "B0 header id " + b0Id + " is a stage-B OpSwitch case; cases=" + switchCasesB);
            }
        }
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }

    // The integer case labels of every OpSwitch in the SPIR-V assembly. Format:
    // OpSwitch %selector %default <lit> %target <lit> %target ...
    private static Set<Integer> opSwitchCaseLiterals(String asm) {
        Set<Integer> cases = new HashSet<>();
        for (String line : asm.split("\n")) {
            int at = line.indexOf("OpSwitch");
            if (at < 0) {
                continue;
            }
            String[] tokens = line.substring(at).trim().split("\\s+");
            for (int i = 3; i + 1 < tokens.length; i += 2) {
                try {
                    cases.add(Integer.parseInt(tokens[i]));
                } catch (NumberFormatException notALiteral) {
                    // A comment token or non-integer operand — skip.
                }
            }
        }
        return cases;
    }
}

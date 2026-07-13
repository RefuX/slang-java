package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M4 exit criterion (DESIGN.md §16): the Slang user guide's reflection walkthrough
 * (docs/user-guide/09-reflection.md) reproduced from Java — variables, types (kinds, fields,
 * vectors, matrices), variable layouts (offsets, bindings), type layouts (sizes), entry points
 * (stage, thread-group size), and the reflection-JSON cross-check.
 */
class ReflectionWalkthroughTest {

    static final String SOURCE = """
            struct Material
            {
                float4   baseColor;
                float3   emissive;
                float    roughness;
                float4x4 uvTransform;
            }

            ConstantBuffer<Material> gMaterial;
            Texture2D                gAlbedoTexture;
            SamplerState             gSampler;
            RWStructuredBuffer<float4> gOutput;

            [shader("compute")]
            [numthreads(8, 4, 2)]
            void computeMain(uint3 tid : SV_DispatchThreadID)
            {
                gOutput[tid.x] = gMaterial.baseColor;
            }
            """;

    static GlobalSession global;
    static Session session;
    static ComponentType linked;
    static ShaderReflection reflection;

    @BeforeAll
    static void compile() {
        global = Slang.createGlobalSession();
        session = global.newSession().target(CompileTarget.HLSL).create();
        Module module = session.loadModuleFromSource("walkthrough", SOURCE);
        linked = session.composite(module, module.entryPoint("computeMain")).link();
        reflection = linked.layout(0);
    }

    @AfterAll
    static void tearDown() {
        linked.close();
        session.close();
        global.close();
    }

    /** User guide "Variables" + "Variable Layouts": names, categories, bindings. */
    @Test
    void parametersReportNamesCategoriesAndBindings() {
        List<VariableLayoutReflection> params = reflection.parameters();
        assertEquals(4, params.size());

        assertEquals("gMaterial", params.get(0).name());
        assertEquals(ParameterCategory.CONSTANT_BUFFER, params.get(0).category());
        assertEquals(0, params.get(0).getBindingIndex()); // b0

        assertEquals("gAlbedoTexture", params.get(1).name());
        assertEquals(ParameterCategory.SHADER_RESOURCE, params.get(1).category());
        assertEquals(0, params.get(1).getBindingIndex()); // t0

        assertEquals("gSampler", params.get(2).name());
        assertEquals(ParameterCategory.SAMPLER_STATE, params.get(2).category());

        assertEquals("gOutput", params.get(3).name());
        assertEquals(ParameterCategory.UNORDERED_ACCESS, params.get(3).category());
        for (VariableLayoutReflection p : params) {
            assertEquals(0, p.getBindingSpace(), p.name() + " binds in space 0");
        }
    }

    /** User guide "Structure Types" + "Type Layouts": kinds, field offsets, sizes (bytes). */
    @Test
    void constantBufferElementLayoutHasClassicPackingOffsets() {
        TypeLayoutReflection cbLayout = reflection.parameters().get(0).typeLayout();
        assertEquals(TypeKind.CONSTANT_BUFFER, cbLayout.kind());

        TypeLayoutReflection material = cbLayout.getElementTypeLayout();
        assertEquals(TypeKind.STRUCT, material.kind());
        assertEquals("Material", material.name());

        List<VariableLayoutReflection> fields = material.fields();
        assertEquals(4, fields.size());
        assertEquals("baseColor", fields.get(0).name());
        assertEquals(0, fields.get(0).offset());
        assertEquals("emissive", fields.get(1).name());
        assertEquals(16, fields.get(1).offset());
        assertEquals("roughness", fields.get(2).name());
        assertEquals(28, fields.get(2).offset(), "packs into the float3's trailing slot");
        assertEquals("uvTransform", fields.get(3).name());
        assertEquals(32, fields.get(3).offset());
        assertEquals(96, material.size(), "16 + 16 + 64 bytes under cbuffer packing");
    }

    /** User guide "Vectors" + "Matrices": element counts and matrix dimensions. */
    @Test
    void vectorAndMatrixTypesReportTheirShape() {
        TypeLayoutReflection material =
                reflection.parameters().get(0).typeLayout().getElementTypeLayout();

        TypeReflection baseColor = material.fields().get(0).variable().type();
        assertEquals(TypeKind.VECTOR, baseColor.kind());
        assertEquals(4, baseColor.elementCount());
        assertEquals(TypeKind.SCALAR, baseColor.getElementType().kind());

        TypeReflection uvTransform = material.fields().get(3).variable().type();
        assertEquals(TypeKind.MATRIX, uvTransform.kind());
        assertEquals(4, uvTransform.getRowCount());
        assertEquals(4, uvTransform.getColumnCount());
    }

    /** User guide "Entry Points": name, stage, compute thread-group size. */
    @Test
    void entryPointReportsStageAndThreadGroupSize() {
        assertEquals(1, reflection.entryPoints().size());
        EntryPointReflection main = reflection.entryPoints().get(0);
        assertEquals("computeMain", main.name());
        assertEquals(Stage.COMPUTE, main.stage());
        assertArrayEquals(new long[] {8, 4, 2}, main.computeThreadGroupSize());

        assertNotNull(reflection.findEntryPointByName("computeMain"));
    }

    /** Cross-check the traversal against Slang's own reflection-JSON emitter. */
    @Test
    void reflectionJsonAgreesWithTheTraversal() {
        String json = reflection.toJson();
        assertTrue(json.contains("\"parameters\""), "JSON has a parameters section");
        for (String name : new String[] {"gMaterial", "gAlbedoTexture", "gSampler", "gOutput"}) {
            assertTrue(json.contains(name), "JSON mentions " + name);
        }
        assertTrue(json.contains("computeMain"), "JSON mentions the entry point");
    }
}

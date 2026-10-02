package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading modules by name, and what a module reports about itself: name, files, entry points, code. */
class ModuleLoadingTest {

    private static final String LIGHTING = """
            module lighting;
            import helper;
            #include "common.slangh"
            RWStructuredBuffer<float> output;

            [shader("compute")] [numthreads(1,1,1)]
            void first(uint3 tid : SV_DispatchThreadID) { output[0] = twice(helperValue()); }

            [shader("compute")] [numthreads(1,1,1)]
            void second(uint3 tid : SV_DispatchThreadID) { output[1] = 1.0; }

            float4 vertexMain(float3 position : POSITION) : SV_Position { return float4(position, 1.0); }
            """;

    @TempDir
    Path dir;

    private void writeSources() throws IOException {
        Files.writeString(dir.resolve("lighting.slang"), LIGHTING);
        Files.writeString(dir.resolve("common.slangh"), "float twice(float x) { return x * 2.0; }\n");
        Files.writeString(dir.resolve("helper.slang"), "module helper;\npublic float helperValue() { return 3.0; }\n");
    }

    private Session searchPathSession(GlobalSession global) {
        return global.newSession().target(CompileTarget.SPIRV).searchPath(dir).create();
    }

    private static List<String> fileNames(List<String> paths) {
        return paths.stream()
                .map(path -> Path.of(path).getFileName().toString())
                .toList();
    }

    @Test
    void loadModuleFindsItsSourceOnTheSearchPath() throws IOException {
        writeSources();
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = searchPathSession(global)) {
            Module module = session.loadModule("lighting");

            assertEquals("lighting", module.name());
            assertEquals(
                    "lighting.slang", Path.of(module.filePath()).getFileName().toString());
            assertEquals(
                    List.of("lighting.slang", "common.slangh", "helper.slang"),
                    fileNames(module.dependencyFiles()),
                    "its source, its #include, and the module it imports");
        }
    }

    @Test
    void loadModuleReturnsTheModuleAlreadyLoaded() throws IOException {
        writeSources();
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = searchPathSession(global)) {
            Module first = session.loadModule("lighting");
            Module again = session.loadModule("lighting");

            assertEquals(
                    first.componentHandle().segment().address(),
                    again.componentHandle().segment().address());
        }
    }

    @Test
    void loadModuleResolvesThroughAJavaFileSystem() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.SPIRV)
                        .fileSystem(SlangFileSystem.ofMap(Map.of(
                                "lighting.slang", LIGHTING,
                                "common.slangh", "float twice(float x) { return x * 2.0; }\n",
                                "helper.slang", "module helper;\npublic float helperValue() { return 3.0; }\n")))
                        .create()) {
            assertEquals("lighting", session.loadModule("lighting").name());
        }
    }

    @Test
    void missingModuleThrows() {
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = searchPathSession(global)) {
            assertThrows(SlangCompileException.class, () -> session.loadModule("no_such_module"));
        }
    }

    @Test
    void entryPointsListsWhatSlangFoundAndStagedLookupFindsTheRest() throws IOException {
        writeSources();
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = searchPathSession(global)) {
            Module module = session.loadModule("lighting");
            List<EntryPoint> entryPoints = module.entryPoints();
            assertEquals(2, entryPoints.size(), "the two [shader] functions, not the undecorated vertexMain");

            List<ComponentType> parts = new ArrayList<>(List.of(module));
            parts.addAll(entryPoints);
            try (ComponentType linked =
                    session.composite(parts.toArray(ComponentType[]::new)).link()) {
                assertEquals(
                        List.of("first", "second"),
                        linked.layout(0).entryPoints().stream()
                                .map(EntryPointReflection::name)
                                .toList());
            }

            try (ComponentType linked = session.composite(module, module.entryPoint("vertexMain", Stage.VERTEX))
                    .link()) {
                EntryPointReflection vertex = linked.layout(0).entryPoints().getFirst();
                assertEquals("vertexMain", vertex.name());
                assertEquals(Stage.VERTEX, vertex.stage());
            }
            assertThrows(SlangCompileException.class, () -> module.entryPoint("noSuchFunction", Stage.COMPUTE));
        }
    }

    @Test
    void targetCodeCoversEveryEntryPoint() throws IOException {
        writeSources();
        try (GlobalSession global = Slang.createGlobalSession();
                Session session = searchPathSession(global)) {
            Module module = session.loadModule("lighting");
            try (ComponentType linked = session.composite(
                            module, module.entryPoint("first"), module.entryPoint("second"))
                    .link()) {
                byte[] spirv = linked.targetCode(0);

                assertEquals(
                        0x0723_0203,
                        ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(0),
                        "SPIR-V magic");
                String text = new String(spirv, StandardCharsets.ISO_8859_1);
                assertTrue(text.contains("first") && text.contains("second"), "one module holding both entry points");
            }
        }
    }

    /** Fresh sessions each time: a session keeps the source files it has read, so it would not see the edit. */
    @Test
    void isIrUpToDateTracksSourcesAndOptions() throws IOException {
        writeSources();
        Path irPath = dir.resolve("lighting.slang-module");
        try (GlobalSession global = Slang.createGlobalSession()) {
            byte[] ir;
            try (Session session = searchPathSession(global)) {
                ir = session.loadModule("lighting").serialize();
            }
            try (Session session = searchPathSession(global)) {
                assertTrue(session.isIrUpToDate(irPath.toString(), ir), "nothing changed");
            }
            try (Session session = global.newSession()
                    .target(CompileTarget.SPIRV)
                    .searchPath(dir)
                    .optimization(OptimizationLevel.MAXIMAL)
                    .create()) {
                assertFalse(session.isIrUpToDate(irPath.toString(), ir), "compiler options changed");
            }
            Files.writeString(dir.resolve("common.slangh"), "float twice(float x) { return x * 3.0; }\n");
            try (Session session = searchPathSession(global)) {
                assertFalse(session.isIrUpToDate(irPath.toString(), ir), "an #included file changed");
            }
            try (Session session = searchPathSession(global)) {
                assertFalse(session.isIrUpToDate(irPath.toString(), new byte[0]), "no IR at all");
            }
        }
    }
}

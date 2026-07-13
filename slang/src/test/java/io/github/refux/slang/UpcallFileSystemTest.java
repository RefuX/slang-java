package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.refux.slang.ffi.JavaComObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M5 exit criterion (DESIGN.md §16): a multi-file module graph — entry module importing brdf,
 * which imports common — compiled with every file served from a Java {@code Map}, through
 * Java-implemented {@code ISlangFileSystem}/{@code ISlangBlob} COM objects (upcalls). Plus the
 * directory-backed file system and refcount lifecycle/stress coverage.
 */
class UpcallFileSystemTest {

    static final Map<String, String> FILES = Map.of(
            "common.slang", """
                    public static const float kScale = 2.0;
                    """,
            "brdf.slang", """
                    import common;
                    public float brdf(float x) { return x * kScale; }
                    """);

    static final String MAIN_SOURCE = """
            import brdf;

            RWStructuredBuffer<float> result;

            [shader("compute")] [numthreads(1,1,1)]
            void main(uint3 tid : SV_DispatchThreadID)
            {
                result[tid.x] = brdf(3.0);
            }
            """;

    @Test
    void multiFileModuleGraphServedFromAJavaMap() {
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        SlangFileSystem base = SlangFileSystem.ofMap(FILES);
        SlangFileSystem recording = path -> {
            requested.add(path);
            return base.loadFile(path);
        };

        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.HLSL)
                        .fileSystem(recording)
                        .create()) {
            Module module = session.loadModuleFromSource("main", MAIN_SOURCE);
            try (ComponentType linked =
                    session.composite(module, module.entryPoint("main")).link()) {
                String hlsl = new String(linked.entryPointCode(0, 0), StandardCharsets.UTF_8);
                assertTrue(hlsl.contains("main"), "compiled through the Java file system");
            }
        }
        assertTrue(
                requested.stream().anyMatch(p -> p.endsWith("brdf.slang")),
                "import brdf resolved through the Java file system; requested: " + requested);
        assertTrue(
                requested.stream().anyMatch(p -> p.endsWith("common.slang")),
                "the nested import also resolved through Java; requested: " + requested);
    }

    @Test
    void directoryBackedFileSystemResolvesImports(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("helper.slang"), """
                public float twice(float x) { return 2.0 * x; }
                """);

        try (GlobalSession global = Slang.createGlobalSession();
                Session session = global.newSession()
                        .target(CompileTarget.HLSL)
                        .fileSystem(SlangFileSystem.ofPath(dir))
                        .create()) {
            Module module = session.loadModuleFromSource("main", """
                    import helper;
                    [shader("compute")] [numthreads(1,1,1)]
                    void main() { float f = twice(21.0); }
                    """);
            try (ComponentType linked =
                    session.composite(module, module.entryPoint("main")).link()) {
                assertTrue(linked.entryPointCode(0, 0).length > 0);
            }
        }
    }

    /**
     * Refcount lifecycle: the file system object (and every blob it produced) must be released
     * once the sessions using it are closed — repeatedly, with one shared Java implementation.
     */
    @Test
    void nativeReferencesAreBalancedAcrossRepeatedCompiles() {
        int baseline = JavaComObject.liveCount();
        SlangFileSystem shared = SlangFileSystem.ofMap(FILES);

        for (int round = 0; round < 30; round++) {
            try (GlobalSession global = Slang.createGlobalSession();
                    Session session = global.newSession()
                            .target(CompileTarget.HLSL)
                            .fileSystem(shared)
                            .create()) {
                assertTrue(JavaComObject.liveCount() > baseline, "the session holds the file system while alive");
                Module module = session.loadModuleFromSource("main", MAIN_SOURCE);
                try (ComponentType linked =
                        session.composite(module, module.entryPoint("main")).link()) {
                    linked.entryPointCode(0, 0);
                }
            }
        }
        assertEquals(
                baseline,
                JavaComObject.liveCount(),
                "all file system and blob objects released after their sessions closed");
    }
}

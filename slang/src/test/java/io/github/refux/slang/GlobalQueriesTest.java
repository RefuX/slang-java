package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** What a global session can say about the Slang build and the downstream compilers it finds. */
class GlobalQueriesTest {

    @Test
    void findCapabilityKnowsCapabilityNames() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertTrue(global.findCapability("spirv_1_5") > 0);
            assertEquals(0, global.findCapability("not_a_capability"));
        }
    }

    /** SPIR-V is emitted directly, so needs no downstream compiler on any platform. */
    @Test
    void spirvIsAlwaysSupported() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertTrue(global.isTargetSupported(CompileTarget.SPIRV));
        }
    }

    /** glslang ships in the natives payload, which the binding points Slang at. */
    @Test
    void glslangIsTheLibraryInThePayload() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertTrue(global.isPassThroughSupported(PassThrough.GLSLANG));

            Path glslang = global.downstreamCompilerPath(PassThrough.GLSLANG).orElseThrow();
            assertTrue(Files.isRegularFile(glslang), glslang + " exists");
            assertTrue(glslang.getFileName().toString().contains("slang-glslang"), glslang.toString());
        }
    }

    /** Clang runs as an executable when found at all, so it never has a library path. */
    @Test
    void executableCompilersHaveNoLibraryPath() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertTrue(global.downstreamCompilerPath(PassThrough.CLANG).isEmpty());
        }
    }

    @Test
    void unknownPassThroughIsRefused() {
        try (GlobalSession global = Slang.createGlobalSession()) {
            assertThrows(IllegalArgumentException.class, () -> global.downstreamCompilerPath(PassThrough.UNKNOWN));
            assertThrows(IllegalArgumentException.class, () -> global.isPassThroughSupported(PassThrough.UNKNOWN));
        }
    }
}

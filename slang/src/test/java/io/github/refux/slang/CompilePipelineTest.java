package io.github.refux.slang;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.refux.slang.ffi.IComponentType;
import io.github.refux.slang.ffi.IEntryPoint;
import io.github.refux.slang.ffi.IGlobalSession;
import io.github.refux.slang.ffi.IModule;
import io.github.refux.slang.ffi.ISession;
import io.github.refux.slang.ffi.PreprocessorMacroDesc;
import io.github.refux.slang.ffi.SessionDesc;
import io.github.refux.slang.ffi.SlangCompileTarget;
import io.github.refux.slang.ffi.TargetDesc;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M1 golden tests (DESIGN.md §16): the full hand-written compile pipeline —
 * createSession → loadModuleFromSourceString → findEntryPointByName →
 * createCompositeComponentType → link → getEntryPointCode — against the pinned Slang binaries,
 * on CPU-only runners (SPIR-V bytes and HLSL text need no GPU).
 */
class CompilePipelineTest {

    static final int SPIRV_MAGIC = 0x0723_0203;

    static final String HELLO_SOURCE = """
        RWStructuredBuffer<float> result;

        [shader("compute")]
        [numthreads(4, 1, 1)]
        void computeMain(uint3 tid : SV_DispatchThreadID)
        {
            result[tid.x] = float(tid.x) * 2.0;
        }
        """;

    static IGlobalSession global;

    @BeforeAll
    static void createGlobalSession() {
        global = IGlobalSession.create();
    }

    @AfterAll
    static void releaseGlobalSession() {
        global.close();
    }

    /** Builds a session with one default-initialized target per given format. */
    private static ISession newSession(int... formats) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment targets = TargetDesc.allocateArray(arena, formats.length);
            for (int i = 0; i < formats.length; i++) {
                MemorySegment target = TargetDesc.element(targets, i);
                TargetDesc.setFormat(target, formats[i]);
                if (formats[i] == SlangCompileTarget.SPIRV) {
                    TargetDesc.setProfile(target, global.findProfile("spirv_1_5"));
                }
            }
            MemorySegment desc = SessionDesc.allocate(arena);
            SessionDesc.setTargets(desc, targets, formats.length);
            return global.createSession(desc);
        }
    }

    /** Runs module → entry point → composite → link → code for target index 0. */
    private static byte[] compileEntryPoint(ISession session, String source) {
        IModule module = session.loadModuleFromSourceString("hello", "hello.slang", source);
        try (IEntryPoint entryPoint = module.findEntryPointByName("computeMain");
                IComponentType composite = session.createCompositeComponentType(module, entryPoint);
                IComponentType linked = composite.link()) {
            return linked.getEntryPointCode(0, 0);
        }
    }

    @Test
    void compilesComputeShaderToSpirv() {
        try (ISession session = newSession(SlangCompileTarget.SPIRV)) {
            byte[] spirv = compileEntryPoint(session, HELLO_SOURCE);
            assertTrue(spirv.length > 20, "SPIR-V module should be non-trivial");
            int magic = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            assertEquals(SPIRV_MAGIC, magic, "SPIR-V magic number");
            System.out.println("[slang-java] SPIR-V: " + spirv.length + " bytes, magic ok");
        }
    }

    @Test
    void compilesComputeShaderToHlslText() {
        try (ISession session = newSession(SlangCompileTarget.HLSL)) {
            String hlsl = new String(compileEntryPoint(session, HELLO_SOURCE), StandardCharsets.UTF_8);
            assertTrue(hlsl.contains("numthreads(4"), "HLSL keeps the numthreads attribute");
            assertTrue(hlsl.contains("computeMain"), "HLSL keeps the entry point name");
        }
    }

    /** One session, two targets: target indices must map to SessionDesc.targets order. */
    @Test
    void multiTargetSessionCompilesBothTargets() {
        try (ISession session = newSession(SlangCompileTarget.SPIRV, SlangCompileTarget.HLSL)) {
            IModule module = session.loadModuleFromSourceString("hello", "hello.slang", HELLO_SOURCE);
            try (IEntryPoint entryPoint = module.findEntryPointByName("computeMain");
                    IComponentType composite = session.createCompositeComponentType(module, entryPoint);
                    IComponentType linked = composite.link()) {
                byte[] spirv = linked.getEntryPointCode(0, 0);
                assertEquals(
                        SPIRV_MAGIC,
                        ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
                String hlsl = new String(linked.getEntryPointCode(0, 1), StandardCharsets.UTF_8);
                assertTrue(hlsl.contains("computeMain"));
            }
        }
    }

    @Test
    void compileErrorsSurfaceAsExceptionWithDiagnostics() {
        try (ISession session = newSession(SlangCompileTarget.SPIRV)) {
            SlangCompileException e = assertThrows(
                    SlangCompileException.class,
                    () -> session.loadModuleFromSourceString("broken", "broken.slang", """
                    void computeMain() { thisTypeDoesNotExist x; }
                    """));
            assertTrue(
                    e.getMessage().contains("error"),
                    "diagnostics text should contain the compiler error: " + e.getMessage());
            assertTrue(e.getMessage().contains("broken.slang"), "diagnostics should reference the synthetic path");
            // The session must remain usable after a failed compile.
            byte[] spirv = compileEntryPoint(session, HELLO_SOURCE);
            assertTrue(spirv.length > 0);
        }
    }

    /**
     * Empirically settles DESIGN.md's SessionDesc lifetime question: createSession must copy the
     * descriptor's contents. We define macro MAGIC_VALUE=41, create the session, then overwrite
     * the macro's value bytes in place *before* compiling. If Slang had retained the pointer, the
     * shader would see 99; because it copies, the emitted HLSL contains 41.
     */
    @Test
    void createSessionCopiesDescriptorContents() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom("MAGIC_VALUE");
            MemorySegment value = arena.allocateFrom("41");
            MemorySegment macros = PreprocessorMacroDesc.allocateArray(arena, 1);
            PreprocessorMacroDesc.set(macros, 0, name, value);

            MemorySegment targets = TargetDesc.allocateArray(arena, 1);
            TargetDesc.setFormat(TargetDesc.element(targets, 0), SlangCompileTarget.HLSL);
            MemorySegment desc = SessionDesc.allocate(arena);
            SessionDesc.setTargets(desc, targets, 1);
            SessionDesc.setPreprocessorMacros(desc, macros, 1);

            try (ISession session = global.createSession(desc)) {
                // Clobber the macro value ("41\0" -> "99\0") after createSession returned.
                value.set(JAVA_BYTE, 0, (byte) '9');
                value.set(JAVA_BYTE, 1, (byte) '9');

                String hlsl = new String(compileEntryPoint(session, """
                    RWStructuredBuffer<float> result;

                    [shader("compute")]
                    [numthreads(4, 1, 1)]
                    void computeMain(uint3 tid : SV_DispatchThreadID)
                    {
                        result[tid.x] = float(MAGIC_VALUE);
                    }
                    """), StandardCharsets.UTF_8);
                assertTrue(hlsl.contains("41"), "session must see the value copied at createSession");
                assertFalse(hlsl.contains("99"), "session must not re-read caller memory");
            }
        }
    }
}

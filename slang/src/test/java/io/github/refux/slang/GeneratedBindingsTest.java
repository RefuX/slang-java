package io.github.refux.slang;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.refux.slang.ffi.IComponentType;
import io.github.refux.slang.ffi.IEntryPoint;
import io.github.refux.slang.ffi.IGlobalSession;
import io.github.refux.slang.ffi.IModule;
import io.github.refux.slang.ffi.ISession;
import io.github.refux.slang.ffi.SessionDesc;
import io.github.refux.slang.ffi.SlangCompileTarget;
import io.github.refux.slang.ffi.TargetDesc;
import io.github.refux.slang.ffi.gen.SlangReflectionAPI;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * M2 proof for generated surface the hand-written veneers do not use yet: direct vtable dispatch
 * through {@code ffi.gen} ({@code IComponentType.getLayout}, slot 4) and the generated
 * {@code spReflection*} C downcalls. Full reflection coverage is milestone M4; this pins that
 * the generated dispatch itself is sound.
 */
class GeneratedBindingsTest {

    static final String SOURCE = """
        RWStructuredBuffer<float> result;

        [shader("compute")]
        [numthreads(4, 1, 1)]
        void computeMain(uint3 tid : SV_DispatchThreadID)
        {
            result[tid.x] = float(tid.x);
        }
        """;

    @Test
    void generatedReflectionApiSeesTheGlobalParameter() {
        try (IGlobalSession global = IGlobalSession.create();
                Arena arena = Arena.ofConfined()) {
            MemorySegment targets = TargetDesc.allocateArray(arena, 1);
            TargetDesc.setFormat(TargetDesc.element(targets, 0), SlangCompileTarget.HLSL);
            MemorySegment desc = SessionDesc.allocate(arena);
            SessionDesc.setTargets(desc, targets, 1);

            try (ISession session = global.createSession(desc)) {
                IModule module = session.loadModuleFromSourceString("g", "g.slang", SOURCE);
                try (IEntryPoint entryPoint = module.findEntryPointByName("computeMain");
                        IComponentType composite = session.createCompositeComponentType(module, entryPoint);
                        IComponentType linked = composite.link()) {

                    // Generated vtable dispatch (slot 4); the returned ProgramLayout is owned by
                    // the component, so it is a borrowed pointer, not refcounted.
                    MemorySegment layout = io.github.refux.slang.ffi.gen.IComponentType.getLayout(
                            linked.segment(), 0, arena.allocate(ADDRESS));
                    assertNotEquals(0, layout.address(), "getLayout returned a ProgramLayout");

                    // Generated C downcall into the reflection ABI.
                    int parameterCount = SlangReflectionAPI.spReflection_GetParameterCount(layout);
                    assertEquals(1, parameterCount, "the shader has exactly one global parameter (the result buffer)");
                }
            }
        }
    }
}

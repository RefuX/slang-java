package io.github.refux.slang.ffi;

import io.github.refux.slang.ffi.gen.SlangLanguageVersion;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Convenience over the generated {@code ffi.gen.SlangGlobalSessionDesc} (size 80): allocation
 * with the same defaults the C++ header initializers use.
 */
public final class SlangGlobalSessionDesc {
    private SlangGlobalSessionDesc() {}

    /** {@code SLANG_API_VERSION} — the API version this binding is written against. */
    public static final int SLANG_API_VERSION = 0;

    /**
     * Allocates a descriptor with the C++ header's defaults ({@code SLANG_API_VERSION},
     * minimum language version 2025, GLSL support off; reserved words zero).
     */
    public static MemorySegment allocate(Arena arena) {
        MemorySegment desc = io.github.refux.slang.ffi.gen.SlangGlobalSessionDesc.allocate(arena);
        io.github.refux.slang.ffi.gen.SlangGlobalSessionDesc.setApiVersion(desc, SLANG_API_VERSION);
        io.github.refux.slang.ffi.gen.SlangGlobalSessionDesc.setMinLanguageVersion(
                desc, SlangLanguageVersion.SLANG_LANGUAGE_VERSION_2025);
        return desc; // structureSize prefilled by the generated allocate()
    }

    public static void setEnableGlsl(MemorySegment desc, boolean enable) {
        io.github.refux.slang.ffi.gen.SlangGlobalSessionDesc.setEnableGLSL(desc, enable);
    }
}

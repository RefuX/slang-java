package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;

/**
 * Memory layout for {@code struct SlangGlobalSessionDesc} (slang.h), the versioned descriptor
 * passed to {@code slang_createGlobalSession2}:
 *
 * <pre>
 * struct SlangGlobalSessionDesc {
 *     uint32_t structureSize;       // = sizeof(SlangGlobalSessionDesc)
 *     uint32_t apiVersion;          // = SLANG_API_VERSION (0)
 *     uint32_t minLanguageVersion;  // = SLANG_LANGUAGE_VERSION_2025 (2025)
 *     bool     enableGLSL;          // + 3 bytes padding
 *     uint32_t reserved[16];
 * };                                // sizeof == 80, alignment 4
 * </pre>
 *
 * M0 hand-written layout; the M2 generator derives this from libclang-reported offsets instead.
 */
public final class SlangGlobalSessionDesc {
    private SlangGlobalSessionDesc() {}

    public static final int SLANG_API_VERSION = 0;
    public static final int SLANG_LANGUAGE_VERSION_2025 = 2025;

    public static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
                    JAVA_INT.withName("structureSize"),
                    JAVA_INT.withName("apiVersion"),
                    JAVA_INT.withName("minLanguageVersion"),
                    JAVA_BOOLEAN.withName("enableGLSL"),
                    MemoryLayout.paddingLayout(3),
                    MemoryLayout.sequenceLayout(16, JAVA_INT).withName("reserved"))
            .withName("SlangGlobalSessionDesc");

    private static final long OFFSET_STRUCTURE_SIZE = offset("structureSize");
    private static final long OFFSET_API_VERSION = offset("apiVersion");
    private static final long OFFSET_MIN_LANGUAGE_VERSION = offset("minLanguageVersion");
    private static final long OFFSET_ENABLE_GLSL = offset("enableGLSL");

    static {
        // Guard the hand-computed layout against drift from the C definition.
        if (LAYOUT.byteSize() != 80) {
            throw new IllegalStateException("SlangGlobalSessionDesc layout must be 80 bytes, got " + LAYOUT.byteSize());
        }
    }

    private static long offset(String field) {
        return LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    /**
     * Allocates a descriptor in {@code arena} with the same defaults the C++ header initializers
     * use (reserved words are zero because arena allocations are zero-initialized).
     */
    public static MemorySegment allocate(Arena arena) {
        MemorySegment desc = arena.allocate(LAYOUT);
        desc.set(JAVA_INT, OFFSET_STRUCTURE_SIZE, (int) LAYOUT.byteSize());
        desc.set(JAVA_INT, OFFSET_API_VERSION, SLANG_API_VERSION);
        desc.set(JAVA_INT, OFFSET_MIN_LANGUAGE_VERSION, SLANG_LANGUAGE_VERSION_2025);
        desc.set(JAVA_BOOLEAN, OFFSET_ENABLE_GLSL, false);
        return desc;
    }

    public static void setEnableGlsl(MemorySegment desc, boolean enable) {
        desc.set(JAVA_BOOLEAN, OFFSET_ENABLE_GLSL, enable);
    }
}

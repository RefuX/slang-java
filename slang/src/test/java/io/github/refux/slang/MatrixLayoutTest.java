package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Pins the matrix layout a session compiles with, and the fact that it is settable at all.
 *
 * <p>Why this is worth a test of its own: {@code SessionDesc}'s header default is row-major while
 * the {@code slangc} command line defaults to column-major, so a project compiling the same shader
 * both ways gets silently transposed matrices in one half. Nothing diagnoses that — both modules
 * are valid SPIR-V — so the only thing standing between a consumer and a day of debugging is this
 * knob and the decoration these assertions read.
 */
class MatrixLayoutTest {

    private static final String SOURCE = """
            struct Pc { float4x4 uProj; };
            [[vk::push_constant]] ConstantBuffer<Pc> pc;
            [shader("vertex")]
            float4 vertexMain(float3 p) { return mul(pc.uProj, float4(p, 1.0)); }
            """;

    private static byte[] compileWith(MatrixLayout layout) {
        try (GlobalSession global = Slang.createGlobalSession()) {
            SessionBuilder builder = global.newSession().target(CompileTarget.SPIRV, t -> t.profile("spirv_1_6"));
            if (layout != null) {
                builder = builder.matrixLayout(layout);
            }
            try (Session session = builder.create()) {
                Module module = session.loadModuleFromSource("matrix_layout_probe", SOURCE);
                try (ComponentType linked = session.composite(module, module.entryPoint("vertexMain"))
                        .link()) {
                    return linked.entryPointCode(0, 0);
                }
            }
        }
    }

    /** SPIR-V decoration opcodes are little-endian words; find OpMemberDecorate's operand. */
    private static boolean hasDecoration(byte[] spirv, int decoration) {
        ByteBuffer words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 5 * 4; offset + 4 <= spirv.length; offset += 4) {
            int word = words.getInt(offset);
            int opcode = word & 0xFFFF;
            int wordCount = word >>> 16;
            if (opcode == OP_MEMBER_DECORATE && wordCount >= 4 && words.getInt(offset + 3 * 4) == decoration) {
                return true;
            }
            if (wordCount == 0) {
                break;
            }
            offset += (wordCount - 1) * 4;
        }
        return false;
    }

    private static final int OP_MEMBER_DECORATE = 72;
    private static final int DECORATION_ROW_MAJOR = 4;
    private static final int DECORATION_COL_MAJOR = 5;

    @Test
    void columnMajorEmitsRowMajorDecorationsMatchingSlangc() {
        byte[] spirv = compileWith(MatrixLayout.COLUMN_MAJOR);

        assertTrue(hasDecoration(spirv, DECORATION_ROW_MAJOR), "COLUMN_MAJOR should emit RowMajor, as slangc does");
    }

    @Test
    void rowMajorEmitsColMajorDecorations() {
        byte[] spirv = compileWith(MatrixLayout.ROW_MAJOR);

        assertTrue(hasDecoration(spirv, DECORATION_COL_MAJOR), "ROW_MAJOR should emit ColMajor");
    }

    @Test
    void theTwoLayoutsProduceDifferentModules() {
        assertNotEquals(
                hasDecoration(compileWith(MatrixLayout.ROW_MAJOR), DECORATION_ROW_MAJOR),
                hasDecoration(compileWith(MatrixLayout.COLUMN_MAJOR), DECORATION_ROW_MAJOR),
                "the setting must actually reach the compiler");
    }

    @Test
    void unsetKeepsTheSessionDescHeaderDefault() {
        assertTrue(
                hasDecoration(compileWith(null), DECORATION_COL_MAJOR),
                "an unset layout keeps SessionDesc's row-major header default, which emits ColMajor");
    }

    @Test
    void rawValuesRoundTrip() {
        for (MatrixLayout layout : MatrixLayout.values()) {
            assertEquals(layout, MatrixLayout.of(layout.value()));
        }
    }
}

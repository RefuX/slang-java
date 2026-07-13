package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Wrapper for {@code ISlangBlob} — Slang's refcounted byte buffer, used for compiled target code
 * and diagnostics text. The buffer pointer is only valid while the blob is alive, so accessors
 * copy into Java arrays; zero-copy views are an M3 concern. Raw vtable dispatch lives in the
 * generated {@code ffi.gen.ISlangBlob}.
 */
public final class ISlangBlob extends IUnknown {

    public ISlangBlob(MemorySegment pointer) {
        super(pointer);
    }

    public long getBufferSize() {
        return io.github.refux.slang.ffi.gen.ISlangBlob.getBufferSize(segment());
    }

    /** Copies the blob contents into a Java array. */
    public byte[] toByteArray() {
        long size = getBufferSize();
        if (size == 0) {
            return new byte[0];
        }
        return io.github.refux.slang.ffi.gen.ISlangBlob.getBufferPointer(segment())
                .reinterpret(size)
                .toArray(JAVA_BYTE);
    }

    /**
     * Decodes the blob as UTF-8 text (diagnostics, textual targets such as HLSL/GLSL/WGSL),
     * dropping a trailing NUL if the producer included one.
     */
    public String toUtf8String() {
        byte[] bytes = toByteArray();
        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == 0) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}

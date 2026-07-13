package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Wrapper for {@code ISlangBlob} — Slang's refcounted byte buffer, used for compiled target code
 * and diagnostics text. The buffer pointer is only valid while the blob is alive, so accessors
 * copy into Java arrays; zero-copy views are an M3 concern.
 */
public final class ISlangBlob extends IUnknown {
    /** Vtable slots after ISlangUnknown: {@code getBufferPointer} = 3, {@code getBufferSize} = 4. */
    public static final int VT_GET_BUFFER_POINTER = 3;

    public static final int VT_GET_BUFFER_SIZE = 4;

    /** {@code void const* (*)(void* self)} */
    private static final MethodHandle MH_ADDRESS_OF_SELF =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code size_t (*)(void* self)} — size_t is 8 bytes on every supported ABI. */
    private static final MethodHandle MH_LONG_OF_SELF =
            SlangNative.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    public ISlangBlob(MemorySegment pointer) {
        super(pointer);
    }

    public long getBufferSize() {
        try {
            return (long) MH_LONG_OF_SELF.invokeExact(fnPtr(VT_GET_BUFFER_SIZE), segment());
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
    }

    /** Copies the blob contents into a Java array. */
    public byte[] toByteArray() {
        try {
            long size = getBufferSize();
            if (size == 0) {
                return new byte[0];
            }
            MemorySegment buffer =
                    (MemorySegment) MH_ADDRESS_OF_SELF.invokeExact(fnPtr(VT_GET_BUFFER_POINTER), segment());
            return buffer.reinterpret(size).toArray(JAVA_BYTE);
        } catch (Throwable t) {
            throw SlangNative.rethrow(t);
        }
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

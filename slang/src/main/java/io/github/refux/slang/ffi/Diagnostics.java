package io.github.refux.slang.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.SlangCompileException;
import io.github.refux.slang.SlangException;
import java.lang.foreign.MemorySegment;

/**
 * Reads and releases {@code ISlangBlob** outDiagnostics} out-params, and converts failed
 * {@code SlangResult}s into exceptions carrying the diagnostics text.
 *
 * <p>Layering note: this is M1 sugar living in the ffi package for convenience. The M2 generated
 * layer stays raw (returns {@code SlangResult}); this policy then moves up into the idiomatic
 * layer (M3), which will also expose success-with-warnings diagnostics that M1 drops.
 */
final class Diagnostics {
    private Diagnostics() {}

    /**
     * Takes ownership of the diagnostics blob written to {@code outDiagSlot} (if any), releases
     * it, and returns its text — or null when there were no diagnostics.
     */
    static String consume(MemorySegment outDiagSlot) {
        MemorySegment blobPointer = outDiagSlot.get(ADDRESS, 0);
        if (blobPointer.address() == 0) {
            return null;
        }
        try (ISlangBlob blob = new ISlangBlob(blobPointer)) {
            String text = blob.toUtf8String();
            return text.isBlank() ? null : text;
        }
    }

    /**
     * Throws on a failed {@code result}: {@link SlangCompileException} with the compiler's text
     * when diagnostics were produced, plain {@link SlangException} otherwise. Always consumes
     * (releases) the diagnostics blob.
     */
    static void check(String operation, int result, MemorySegment outDiagSlot) {
        String diagnostics = consume(outDiagSlot);
        if (SlangNative.succeeded(result)) {
            return;
        }
        if (diagnostics != null) {
            throw new SlangCompileException(diagnostics, result);
        }
        throw new SlangException(operation + " failed: 0x" + Integer.toHexString(result), result);
    }
}

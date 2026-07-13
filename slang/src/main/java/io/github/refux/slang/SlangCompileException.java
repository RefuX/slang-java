package io.github.refux.slang;

/**
 * Compilation failed. The exception message is the Slang compiler's diagnostics text verbatim
 * (file, line, error code, and message — the same text {@code slangc} would print).
 */
public class SlangCompileException extends SlangException {
    public SlangCompileException(String diagnostics, int result) {
        super(diagnostics, result);
    }
}

package io.github.refux.slang;

/**
 * Unchecked exception for a failed Slang native call. Carries the raw {@code SlangResult}
 * (COM-HRESULT-like: negative values are failures, with facility and code bits).
 */
public class SlangException extends RuntimeException {
    private final int result;

    public SlangException(String message, int result) {
        super(message);
        this.result = result;
    }

    /** The raw {@code SlangResult} that triggered this exception. */
    public int result() {
        return result;
    }
}

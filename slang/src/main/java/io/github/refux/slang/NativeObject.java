package io.github.refux.slang;

import io.github.refux.slang.ffi.IUnknown;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base of the idiomatic wrappers: pairs an owned native handle with deterministic release.
 *
 * <p>Lifecycle contract: {@link #close()} releases the native reference exactly once
 * (idempotent and thread-safe). A wrapper that is never closed is released eventually by a
 * {@link Cleaner} once it becomes unreachable, so a forgotten {@code close()} cannot corrupt the
 * process — but explicit closing (try-with-resources) is preferred for deterministic native
 * memory usage. With {@code -Dio.github.refux.slang.debug=true}, every Cleaner-released wrapper
 * logs a leak warning carrying its allocation-site stack trace.
 */
public abstract class NativeObject implements AutoCloseable {
    /** Enables leak tracing and thread-confinement checks ({@code io.github.refux.slang.debug}). */
    static final boolean DEBUG = Boolean.getBoolean("io.github.refux.slang.debug");

    private static final Cleaner CLEANER = Cleaner.create();
    private static final System.Logger LOG = System.getLogger("io.github.refux.slang");

    private final IUnknown handle;
    private final ReleaseAction release;
    private final Cleaner.Cleanable cleanable;

    NativeObject(IUnknown handle) {
        this.handle = handle;
        this.release =
                new ReleaseAction(handle, getClass().getSimpleName(), DEBUG ? new Throwable("allocated here") : null);
        this.cleanable = CLEANER.register(this, release);
    }

    /**
     * The underlying low-level wrapper, for interop with the {@code ffi} layer. Throws
     * {@link IllegalStateException} once this object is closed.
     */
    final IUnknown handle() {
        if (release.released.get()) {
            throw new IllegalStateException(getClass().getSimpleName() + " is closed");
        }
        return handle;
    }

    public final boolean isClosed() {
        return release.released.get();
    }

    @Override
    public final void close() {
        release.explicit = true;
        cleanable.clean();
    }

    /**
     * The release action must not capture the wrapper itself (that would keep it reachable and
     * defeat the Cleaner); it holds only what release needs.
     */
    private static final class ReleaseAction implements Runnable {
        final IUnknown handle;
        final String what;
        final Throwable origin;
        final AtomicBoolean released = new AtomicBoolean();
        volatile boolean explicit;

        ReleaseAction(IUnknown handle, String what, Throwable origin) {
            this.handle = handle;
            this.what = what;
            this.origin = origin;
        }

        @Override
        public void run() {
            released.set(true);
            if (!explicit && DEBUG) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        what + " was never closed; released by the Cleaner instead",
                        origin);
            }
            handle.close();
        }
    }
}

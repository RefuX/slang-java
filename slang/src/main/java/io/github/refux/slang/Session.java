package io.github.refux.slang;

import io.github.refux.slang.ffi.ISession;
import java.util.function.Consumer;

/**
 * A compilation scope: loads modules, composes them with entry points, and owns everything
 * loaded through it (modules stay valid until the session closes).
 *
 * <p><b>Threading:</b> a session and the objects created from it are confined to one thread at a
 * time (Slang does not synchronize internally). With {@code -Dio.github.refux.slang.debug=true}
 * the confinement is asserted; concurrent compilation wants one session per thread
 * (DESIGN.md §11).
 */
public final class Session extends NativeObject {
    private final ISession session;
    private final Consumer<String> onDiagnostics;
    private final Thread owner = Thread.currentThread();

    Session(ISession session, Consumer<String> onDiagnostics) {
        super(session);
        this.session = session;
        this.onDiagnostics = onDiagnostics;
    }

    /**
     * Compiles Slang source text into a {@link Module} named {@code name} (the name other
     * modules would {@code import} it by; diagnostics reference {@code <name>.slang}).
     *
     * @throws SlangCompileException with the compiler's diagnostics text when compilation fails;
     *     success-with-warnings text goes to the {@link SessionBuilder#onDiagnostics} consumer
     */
    public Module loadModuleFromSource(String name, String source) {
        checkThread();
        return new Module(this, session.loadModuleFromSourceString(name, name + ".slang", source, onDiagnostics));
    }

    /**
     * Combines modules and entry points into one unit of shader code; the order determines the
     * parameter layout order. The result is typically {@link ComponentType#link() linked} next.
     */
    public ComponentType composite(ComponentType... components) {
        checkThread();
        io.github.refux.slang.ffi.IComponentType[] handles =
                new io.github.refux.slang.ffi.IComponentType[components.length];
        for (int i = 0; i < components.length; i++) {
            handles[i] = components[i].componentHandle();
        }
        return new ComponentType(this, session.createCompositeComponentType(handles));
    }

    /** Aliveness + (in debug mode) thread-confinement check for session-scoped operations. */
    void checkThread() {
        handle();
        if (NativeObject.DEBUG && Thread.currentThread() != owner) {
            throw new IllegalStateException("Session is confined to " + owner + " but was used from "
                    + Thread.currentThread()
                    + "; sessions are not thread-safe (DESIGN.md §11)");
        }
    }
}

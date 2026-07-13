package io.github.refux.slang;

import io.github.refux.slang.ffi.IComponentType;

/**
 * A unit of shader code — a module, an entry point, a composite, or a linked program. Linked
 * components are where target code and reflection come from.
 */
public class ComponentType extends NativeObject {
    private final Session session;
    private final IComponentType component;

    ComponentType(Session session, IComponentType component) {
        super(component);
        this.session = session;
        this.component = component;
    }

    /**
     * Links this component against its remaining dependencies (typically the modules its entry
     * points came from), returning a new component ready for code retrieval.
     *
     * @throws SlangCompileException with the compiler's diagnostics on link failure
     */
    public ComponentType link() {
        session.checkThread();
        return new ComponentType(session, componentHandle().link());
    }

    /**
     * Target code for one entry point of a fully linked component: a SPIR-V/DXIL binary, or
     * UTF-8 source bytes for text targets (HLSL, GLSL, WGSL, Metal, ...).
     *
     * @param entryPointIndex index among the entry points composed into this component
     * @param targetIndex index into the session's target list, in {@link SessionBuilder#target}
     *     order
     */
    public byte[] entryPointCode(long entryPointIndex, long targetIndex) {
        session.checkThread();
        return componentHandle().getEntryPointCode(entryPointIndex, targetIndex);
    }

    /** Reflection for {@code targetIndex} (an eager snapshot in M3; the full lazy tree is M4). */
    public ShaderReflection layout(long targetIndex) {
        session.checkThread();
        return new ShaderReflection(this, targetIndex);
    }

    /** The owning session (also keeps it reachable while any component is alive). */
    final Session session() {
        return session;
    }

    /** Aliveness-checked low-level handle, for package internals and ffi interop. */
    final IComponentType componentHandle() {
        handle();
        return component;
    }
}

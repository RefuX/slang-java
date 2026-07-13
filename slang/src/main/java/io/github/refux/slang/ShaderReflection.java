package io.github.refux.slang;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.refux.slang.ffi.ISlangBlob;
import io.github.refux.slang.ffi.SlangNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.AbstractList;
import java.util.List;

/**
 * Reflection over a linked program for one target ({@code slang::ShaderReflection}, a.k.a.
 * {@code ProgramLayout}) — the root of the lazy reflection tree: global parameters, entry
 * points, and type/layout queries. Obtained from {@link ComponentType#layout(long)}; the
 * component stays reachable through this object, keeping the native reflection data alive.
 */
// Reads the borrowed, component-owned native reflection handle (never closed here); the resource
// inspection can't tell it isn't ours to close.
@SuppressWarnings("resource")
public final class ShaderReflection extends io.github.refux.slang.gen.ShaderReflection {
    @SuppressWarnings("unused") // reachability: the component owns the native reflection data
    private final ComponentType component;

    ShaderReflection(ComponentType component, long targetIndex) {
        super(component.componentHandle().getLayout(targetIndex), component);
        this.component = component;
    }

    /** Wraps a raw {@code SlangReflection*}; normally obtained via {@link ComponentType#layout}. */
    public ShaderReflection(MemorySegment self, Object owner) {
        super(self, owner);
        this.component = owner instanceof ComponentType c ? c : null;
    }

    /**
     * The program's top-level shader parameters as a lazy list view. Note the user guide's
     * caveat: for global <em>uniform</em> parameters grouped into an implicit constant buffer,
     * prefer traversing {@link #getGlobalParamsVarLayout()}.
     */
    public List<VariableLayoutReflection> parameters() {
        int count = getParameterCount();
        return new AbstractList<>() {
            @Override
            public VariableLayoutReflection get(int index) {
                return getParameterByIndex(index);
            }

            @Override
            public int size() {
                return count;
            }
        };
    }

    /** The program's entry points as a lazy list view. */
    public List<EntryPointReflection> entryPoints() {
        int count = (int) getEntryPointCount();
        return new AbstractList<>() {
            @Override
            public EntryPointReflection get(int index) {
                return getEntryPointByIndex(index);
            }

            @Override
            public int size() {
                return count;
            }
        };
    }

    /**
     * Finds a top-level shader parameter by name (e.g. a global {@code StructuredBuffer<T>}),
     * or null if the program has no such parameter. Linear scan over {@link #parameters()}.
     */
    public VariableLayoutReflection findParameter(String name) {
        for (VariableLayoutReflection parameter : parameters()) {
            if (name.equals(parameter.name())) {
                return parameter;
            }
        }
        return null;
    }

    /** The whole reflection tree serialized as JSON (Slang's own reflection-JSON emitter). */
    public String toJson() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outBlob = arena.allocate(ADDRESS);
            int result = toJson(MemorySegment.NULL, outBlob);
            if (!SlangNative.succeeded(result)) {
                throw new SlangException("spReflection_ToJson failed", result);
            }
            try (ISlangBlob blob = new ISlangBlob(outBlob.get(ADDRESS, 0))) {
                return blob.toUtf8String();
            }
        }
    }

    /** A human-readable dump of the reflection (the reflection JSON), for diagnostics. */
    public String dump() {
        return toJson();
    }
}

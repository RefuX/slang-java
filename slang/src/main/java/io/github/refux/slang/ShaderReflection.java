package io.github.refux.slang;

import io.github.refux.slang.ffi.SlangNative;
import io.github.refux.slang.ffi.gen.SlangReflectionAPI;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection over a linked program for one target — in M3 an <em>eager snapshot</em> of the
 * top-level shader parameters, copied out of the native reflection data at construction so it
 * has no lifetime coupling to the component. Milestone M4 replaces this with the full lazy
 * reflection tree (types, layouts, entry points) mirroring slang.h's reflection classes.
 */
public final class ShaderReflection {

    /** One top-level shader parameter. */
    public record Parameter(String name, ParameterCategory category) {}

    private final List<Parameter> parameters;

    ShaderReflection(ComponentType component, long targetIndex) {
        // Borrowed pointer, owned by the component; everything is copied out before returning,
        // and `component` stays strongly reachable (caller frame) for the duration.
        MemorySegment layout = component.componentHandle().getLayout(targetIndex);
        int count = SlangReflectionAPI.spReflection_GetParameterCount(layout);
        List<Parameter> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            MemorySegment parameter = SlangReflectionAPI.spReflection_GetParameterByIndex(layout, i);
            MemorySegment variable = SlangReflectionAPI.spReflectionVariableLayout_GetVariable(parameter);
            String name = SlangNative.readUtf8(SlangReflectionAPI.spReflectionVariable_GetName(variable));
            MemorySegment typeLayout = SlangReflectionAPI.spReflectionVariableLayout_GetTypeLayout(parameter);
            ParameterCategory category =
                    ParameterCategory.of(SlangReflectionAPI.spReflectionTypeLayout_GetParameterCategory(typeLayout));
            out.add(new Parameter(name, category));
        }
        this.parameters = List.copyOf(out);
    }

    /** The program's top-level shader parameters, in layout order. */
    public List<Parameter> parameters() {
        return parameters;
    }
}

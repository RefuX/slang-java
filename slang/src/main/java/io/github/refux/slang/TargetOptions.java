package io.github.refux.slang;

/**
 * Per-target configuration collected by {@link SessionBuilder#target}. Unset options keep the
 * C++ header defaults ({@code TargetDesc}'s initializers, including
 * {@code flags = kDefaultTargetFlags}).
 */
public final class TargetOptions {
    private final CompileTarget target;
    String profileName;
    Integer flags;
    Boolean forceGlslScalarBufferLayout;

    TargetOptions(CompileTarget target) {
        this.target = target;
    }

    public CompileTarget target() {
        return target;
    }

    /** Compilation profile by name, e.g. {@code "spirv_1_5"} or {@code "cs_5_0"}. */
    public TargetOptions profile(String name) {
        this.profileName = name;
        return this;
    }

    /** Replaces the target flags (default {@code kDefaultTargetFlags}). */
    public TargetOptions flags(int flags) {
        this.flags = flags;
        return this;
    }

    /** Forces {@code scalar} layout for GLSL shader storage buffers. */
    public TargetOptions forceGlslScalarBufferLayout(boolean force) {
        this.forceGlslScalarBufferLayout = force;
        return this;
    }
}

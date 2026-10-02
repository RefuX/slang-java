package io.github.refux.slang;

/**
 * Per-target configuration collected by {@link SessionBuilder#target}. Unset options keep the
 * C++ header defaults ({@code TargetDesc}'s initializers, including
 * {@code flags = kDefaultTargetFlags}), and compiler options set here override the session's for
 * this target only.
 */
public final class TargetOptions {
    private final CompileTarget target;
    final OptionList compilerOptions = new OptionList();
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

    /**
     * Sets a compiler option for this target only, by its raw {@code slang::CompilerOptionName}
     * value — see {@link SessionBuilder#option(int, int)}. This overload is for int and enum options.
     */
    public TargetOptions option(int name, int value) {
        return option(name, value, 0);
    }

    /** As {@link #option(int, int)}, for bool options. */
    public TargetOptions option(int name, boolean value) {
        return option(name, value ? 1 : 0);
    }

    /** As {@link #option(int, int)}, for the few options that take two ints. */
    public TargetOptions option(int name, int value0, int value1) {
        compilerOptions.add(name, value0, value1);
        return this;
    }

    /** As {@link #option(int, int)}, for string options. */
    public TargetOptions option(int name, String value) {
        compilerOptions.add(name, value);
        return this;
    }
}

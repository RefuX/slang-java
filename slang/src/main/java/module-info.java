/**
 * slang-java: idiomatic Java bindings for the Slang shader compiler over the Foreign Function &amp;
 * Memory API. A named JPMS module so consumers (and OSGi frameworks such as Atomos that bridge
 * module-path modules into bundles) see a stable module name and explicit exports rather than an
 * automatic module derived from the jar file name.
 *
 * <p>All packages are exported: the idiomatic API ({@code io.github.refux.slang}) and its generated
 * bases ({@code .gen}), plus the lower-level FFI layer ({@code .ffi}, {@code .ffi.gen}) and the
 * native library loader ({@code .loader}) for advanced use. Native access must be granted to this
 * module at launch ({@code --enable-native-access=io.github.refux.slang}); everything it needs is in
 * {@code java.base} (notably {@code java.lang.foreign}), so it declares no additional {@code requires}.
 */
module io.github.refux.slang {
    exports io.github.refux.slang;
    exports io.github.refux.slang.gen;
    exports io.github.refux.slang.ffi;
    exports io.github.refux.slang.ffi.gen;
    exports io.github.refux.slang.loader;
}

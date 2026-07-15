package io.github.refux.slang;

/**
 * What a serialized module declares about itself, read without loading it — see
 * {@link Session#moduleInfo(byte[])}.
 *
 * @param moduleVersion the serialized-module format version. IR loads only into a build that reads
 *     this version; compare with {@link GlobalSession#supportedModuleVersion()}. This — not
 *     {@link #compilerVersion()} — is the compatibility axis: two different Slang releases that
 *     emit the same module version produce mutually loadable IR.
 * @param compilerVersion the build tag of the Slang release that wrote the IR, e.g. {@code "2026.8"}.
 *     Informational: useful in diagnostics, but not a compatibility test.
 * @param name the module's own name, as other modules would {@code import} it
 */
public record ModuleInfo(long moduleVersion, String compilerVersion, String name) {}

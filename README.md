# slang-java

[![CI](https://github.com/RefuX/slang-java/actions/workflows/ci.yml/badge.svg)](https://github.com/RefuX/slang-java/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/RefuX/slang-java?include_prereleases&label=release)](https://github.com/RefuX/slang-java/releases)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.refux/slang-java?label=maven%20central)](https://central.sonatype.com/artifact/io.github.refux/slang-java)

Java bindings for the [Slang](https://github.com/shader-slang/slang) shading-language compiler.
Compile Slang source to SPIR-V, DXIL, HLSL, GLSL, WGSL, or Metal from the JVM, inspect the
result with Slang's full reflection API, and resolve `import`s through your own code — pack
files, classpath resources, in-memory maps. Pure Java on the FFM API (`java.lang.foreign`):
no JNI, no native glue of its own — it binds the official Khronos-signed Slang binaries
directly. Windows, macOS, and Linux, on x86_64 and aarch64. Requires JDK 25+.

## Coordinates

| Artifact | Purpose |
|---|---|
| `io.github.refux:slang-java:0.0.1` | The library: compiler API, reflection, Java file systems |
| `io.github.refux:slang-java-natives-<os>-<arch>:0.0.1` | The official Slang binaries for one platform: `windows`, `linux`, or `macos` × `x86_64` or `aarch64` |

## Getting started

Add the library plus the natives artifact for each platform you run on — they can all sit on
the classpath together, and the right one is picked at runtime:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.refux:slang-java:0.0.1")
    runtimeOnly("io.github.refux:slang-java-natives-macos-aarch64:0.0.1")
    runtimeOnly("io.github.refux:slang-java-natives-windows-x86_64:0.0.1")
    runtimeOnly("io.github.refux:slang-java-natives-linux-x86_64:0.0.1")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED") // FFM restricted methods, JEP 472
}
```

Then compile a shader and reflect on it:

```java
import io.github.refux.slang.*;

try (GlobalSession global = Slang.createGlobalSession();
     Session session = global.newSession()
         .target(CompileTarget.SPIRV, t -> t.profile("spirv_1_5"))
         .create()) {

    Module module = session.loadModuleFromSource("hello", """
        RWStructuredBuffer<float> result;

        [shader("compute")] [numthreads(8,8,1)]
        void main(uint3 tid : SV_DispatchThreadID)
        {
            result[tid.x] = float(tid.x) * 2.0;
        }
        """);

    try (ComponentType linked = session.composite(module, module.entryPoint("main")).link()) {
        byte[] spirv = linked.entryPointCode(0, 0); // hand to Vulkan, cache to disk, ...

        ShaderReflection reflection = linked.layout(0);
        for (var parameter : reflection.parameters()) {
            System.out.println(parameter.name() + " : " + parameter.category());
        }
    }
}
```

Compile errors throw `SlangCompileException` carrying the compiler's diagnostics text; warnings
go to an optional `onDiagnostics` consumer on the session builder. Imports can be served from
Java instead of the OS file system:

```java
Session session = global.newSession()
    .target(CompileTarget.SPIRV)
    .fileSystem(SlangFileSystem.ofMap(Map.of(
        "common.slang", "public static const float kScale = 2.0;")))
    .create();
```

Everything is `AutoCloseable` with try-with-resources as the idiom; anything left unclosed is
released by a Cleaner when unreachable, and `-Dio.github.refux.slang.debug=true` traces leaks
to their allocation site and asserts session thread confinement.

To use a different Slang build than the bundled one (say, a local checkout), set
`-Dio.github.refux.slang.libraryPath=<dir>` or `SLANG_JAVA_LIBRARY_PATH` to any directory
holding the Slang libraries — it takes precedence over the natives artifacts.

## How it works

- The low-level layer is **generated from slang.h itself**: COM vtable dispatch, structs with
  clang-verified offsets, all enums, and the complete reflection C ABI — validated byte-identical
  across all six OS/architecture ABIs, with an append-only lockfile guarding against upstream
  ABI drift.
- Java-implemented Slang interfaces (file systems, blobs) are real refcounted COM objects backed
  by FFM upcall stubs.
- The native binaries are the unmodified official Slang releases — nothing is rebuilt or patched.

## Development

Architecture, decisions, and status: [DESIGN.md](DESIGN.md). Contributor commands:
[CLAUDE.md](CLAUDE.md).

Licensed under the Apache License 2.0.

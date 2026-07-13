# slang-java

[![CI](https://github.com/RefuX/slang-java/actions/workflows/ci.yml/badge.svg)](https://github.com/RefuX/slang-java/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/RefuX/slang-java?include_prereleases&label=release)](https://github.com/RefuX/slang-java/releases)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.refux/slang-java?label=maven%20central)](https://central.sonatype.com/artifact/io.github.refux/slang-java)

Java bindings for the [Slang](https://github.com/shader-slang/slang) shading-language compiler,
built on the Java Foreign Function & Memory API (FFM) — **pure Java, no JNI, no native glue of
our own**. Binds the official Khronos-signed Slang release binaries directly.

**Status: milestone M5 (upcalls) complete.** Slang interfaces can now be implemented *in Java*:
`import`/`#include` resolve through a `SlangFileSystem` (map- or directory-backed, or any
lambda) via Java-implemented `ISlangFileSystem`/`ISlangBlob` COM objects — upcall stubs,
registry-pinned instances, Java-side refcounts, with a stress test proving every native
reference balances.

Previously, milestone M4: The full lazy reflection tree mirrors slang.h's
C++ wrapper classes — 11 generated typed classes (166 methods) with hand-polished veneers:
`linked.layout(0).parameters()`, struct field offsets, vector/matrix shapes, entry-point stage
and thread-group size, plus `toJson()` via Slang's own reflection-JSON emitter. The Slang user
guide's reflection walkthrough runs from Java as the acceptance test.

Previously, milestone M3: The "What this will look like" sample
below is now real code — it runs verbatim in the test suite, with try-with-resources lifetimes
(plus a Cleaner safety net for unclosed wrappers and `-Dio.github.refux.slang.debug=true` leak
tracing), compiler warnings delivered to a per-session consumer, and compile errors thrown as
`SlangCompileException` carrying the compiler's diagnostics.

Previously, milestone M2: The low-level layer is now **generated**:
a libclang extractor parses the real slang.h, validates the ABI across all six target triples,
and emits the committed API model ([api/slang-api.json](api/slang-api.json)) plus an append-only
ABI lockfile; a dependency-free Java codegen turns that into 106 source files — all 27 COM
interfaces (150 methods), 55 enums, 21 structs, and 195 C downcalls including the full
`spReflection*` surface. The hand-written classes are thin veneers on top, and the M0/M1 test
suite passed on the generated bindings without touching a test file.
See **[DESIGN.md](DESIGN.md)** for the full design document and the M0–M6 plan.

Upgrading to a new Slang release is now a regenerate-and-review operation:

```sh
bindgen/extract/.venv/bin/python bindgen/extract/extract_api.py \
    --slang-include <slang-repo>/include --slang-version <ver> \
    --out api/slang-api.json --lock api/slang-abi.lock
./gradlew :bindgen:run --args="api/slang-api.json slang/src/main/java"
```

## Try it

```sh
# Fetch the pinned official Slang release for this machine (verified against the
# committed manifest) and run the smoke tests:
./gradlew :natives:downloadNatives
./gradlew :slang:test -PslangNativesDir=natives/build/payload/macos-aarch64/lib   # your os-arch
```

Or point the loader at any Slang build (e.g. a local checkout):

```sh
SLANG_JAVA_LIBRARY_PATH=$SLANG_REPO/build/Release/lib ./gradlew :slang:test
```

The JDK 25 toolchain is auto-provisioned via foojay if not installed; only a JDK able to run
Gradle is needed. Tests pass `--enable-native-access=ALL-UNNAMED` (JEP 472) automatically.

## What the API looks like (working today)

```java
try (GlobalSession global = Slang.createGlobalSession();
     Session session = global.newSession()
         .target(CompileTarget.SPIRV, t -> t.profile("spirv_1_5"))
         .create()) {
    Module module = session.loadModuleFromSource("hello", source);
    try (ComponentType linked = session.composite(module, module.entryPoint("main")).link()) {
        byte[] spirv = linked.entryPointCode(0, 0);
        ShaderReflection reflection = linked.layout(0);
    }
}
```

## Design pillars

- **FFM-only** (JDK 25 LTS baseline; the API itself needs only JDK 22): COM vtable dispatch,
  C downcalls, and Java-implemented Slang interfaces (upcalls) — all in `java.lang.foreign`.
- **Generated low-level layer** (`slang-bindgen`: libclang extractor → committed JSON API model →
  Java codegen) + **hand-written idiomatic layer**.
- **LWJGL-style distribution**: `io.github.refux:slang-java` + per-platform natives jars repackaging
  the unmodified official release libraries (Windows/macOS/Linux, x86_64/aarch64).

## Repository contents

| Path | What |
|---|---|
| [DESIGN.md](DESIGN.md) | Design document + milestone plan (M0–M6, with per-milestone status) |
| `slang/` | The library: generated low-level layer (`ffi/gen`, 106 files) + hand-written veneers (loader, ownership, ergonomics) + smoke/golden tests |
| `bindgen/` | slang-bindgen: libclang extractor (`extract/extract_api.py`) + Java codegen (`:bindgen:run`) |
| `api/` | Committed API model (slang-api.json) and append-only ABI lockfile (slang-abi.lock) |
| [tools/abi-probe.cpp](tools/abi-probe.cpp) | Compiler-verified struct offsets used to cross-check the M1 hand-written layouts |
| `natives/` | `downloadNatives` task: fetches pinned release archives, verifies SHA-256 manifests, extracts payloads |
| `natives/manifests/` | Committed per-platform payload manifests for Slang v2026.13 (all six os-arch combos) |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | Five-platform smoke matrix (Intel-mac best-effort; windows-aarch64 descoped for now) |
| [tools/api-scan.py](tools/api-scan.py) | Prototype scanner of `slang.h`; seed of the M2 generator |

## Releasing

Push a tag matching the version in `slang/build.gradle.kts` (e.g. `v0.0.1`) —
[release.yml](.github/workflows/release.yml) runs the test suite against the pinned binaries,
publishes `io.github.refux:slang-java` to Maven Central (auto-released), and creates the GitHub
Release. Same shape and secrets as slang-wasm-endive.

## Remaining M6 work

Per-platform natives jars (and their classpath loader step), a javadoc pass, and the weekly
ABI-drift canary.

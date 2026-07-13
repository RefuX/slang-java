# slang-java

Java bindings for the [Slang](https://github.com/shader-slang/slang) shading-language compiler,
built on the Java Foreign Function & Memory API (FFM) — **pure Java, no JNI, no native glue of
our own**. Binds the official Khronos-signed Slang release binaries directly.

**Status: milestone M0 (walking skeleton) passing.** The core mechanism is proven: a COM vtable
call (`IGlobalSession::getBuildTagString`, slot 8) and the C export (`spGetBuildTagString`)
return the same build tag from the official Slang v2026.13 binaries.
See **[DESIGN.md](DESIGN.md)** for the full design document and the M0–M6 plan.

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

## What the final API will look like

```java
try (GlobalSession global = Slang.createGlobalSession();
     Session session = global.newSession()
         .target(CompileTarget.SPIRV)
         .create()) {
    Module module = session.loadModule("hello");
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
- **LWJGL-style distribution**: `io.github.refux:slang` + per-platform natives jars repackaging
  the unmodified official release libraries (Windows/macOS/Linux, x86_64/aarch64).

## Repository contents

| Path | What |
|---|---|
| [DESIGN.md](DESIGN.md) | Design document + milestone plan (M0–M6, with per-milestone status) |
| `slang/` | The library. M0: hand-written micro-binding (loader, C downcalls, COM dispatch) + smoke tests |
| `natives/` | `downloadNatives` task: fetches pinned release archives, verifies SHA-256 manifests, extracts payloads |
| `natives/manifests/` | Committed per-platform payload manifests for Slang v2026.13 (all six os-arch combos) |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | Five-platform smoke matrix (Intel-mac best-effort; windows-aarch64 descoped for now) |
| [tools/api-scan.py](tools/api-scan.py) | Prototype scanner of `slang.h`; seed of the M2 generator |

## Next milestone

**M1** — hand-written compile pipeline: `SessionDesc`/`TargetDesc` layouts, source → SPIR-V/HLSL
golden tests, diagnostics-as-exceptions, and settling descriptor lifetime semantics empirically.

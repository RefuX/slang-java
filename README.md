# slang-java

Java bindings for the [Slang](https://github.com/shader-slang/slang) shading-language compiler,
built on the Java Foreign Function & Memory API (FFM) — **pure Java, no JNI, no native glue of
our own**. Binds the official Khronos-signed Slang release binaries directly.

**Status: milestone M3 (idiomatic core API) complete.** The "What this will look like" sample
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
- **LWJGL-style distribution**: `io.github.refux:slang` + per-platform natives jars repackaging
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

## Next milestone

**M4** — reflection: the lazy reflection tree mirroring slang.h's C++ wrapper classes
(types, layouts, entry points), generated from the API model's 171 wrapper mappings over the
173 `spReflection*` downcalls that are already bound.

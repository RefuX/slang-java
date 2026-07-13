# slang-java — Design & Implementation Plan

| | |
|---|---|
| **Status** | Draft for review |
| **Date** | 2026-07-13 |
| **Scope** | Java bindings for the Slang shading-language compiler ([shader-slang/slang](https://github.com/shader-slang/slang)) |
| **Java baseline** | JDK 25 LTS, FFM (java.lang.foreign) |
| **Slang baseline** | v2026.13 release binaries (pinned, upgradable) |
| **Platforms** | Windows, macOS, Linux — x86_64 and aarch64 |

This document was written against a structural scan of `include/slang.h` at tag-adjacent
commit `804fd638a` (v2026.13 era). The scan tool lives in [`tools/api-scan.py`](tools/api-scan.py)
and all interface/method counts below are its output, re-runnable at any time.

---

## 1. Goals and non-goals

**Goals**

1. Call the Slang compiler from Java: load/compile Slang source, link, retrieve target code
   (SPIR-V, DXIL, HLSL, GLSL, WGSL, Metal, …) and use the full reflection API.
2. **Pure Java.** No JNI glue, no C++ shim library, no native code of our own to build or ship.
   We bind the *official, signed* Slang release binaries directly via the Foreign Function &
   Memory API (FFM, final since JDK 22).
3. Cross-platform out of the box: one `implementation("io.github.refux:slang")` dependency plus a
   per-platform natives artifact, LWJGL-style, working on Windows/macOS/Linux, x86_64/aarch64.
4. Two API layers: a **generated low-level layer** that is mechanically faithful to `slang.h`, and
   a **hand-written idiomatic layer** (try-with-resources, exceptions, enums, builders).
5. A **binding generator** (`slang-bindgen`) so that upgrading to a new Slang release is a
   regenerate-and-review operation, not a rewrite.

**Non-goals (initially)**

- The graphics layer: `slang-gfx.h` is legacy and its successor (slang-rhi) is a separate
  project. We produce shader *bytes*; executing them is the job of LWJGL/your engine.
- The deprecated `spCompileRequest` API (`slang-deprecated.h` §compile-request). We bind the
  modern COM API only. (Note the reflection C functions also live in that header — those we
  *do* bind; see §3.3.)
- Record/replay, `IByteCodeRunner` (the interpreter), and `IModulePrecompileService_Experimental`
  — deferred; the generator will still emit them behind an "experimental" flag.
- GPU execution tests. All CI validation uses text/bytecode targets, which need no GPU.

---

## 2. Why FFM, and why not the alternatives

| Approach | Verdict | Reason |
|---|---|---|
| **FFM (chosen)** | ✅ | Final since JDK 22 (JEP 454). Downcalls, upcalls, struct layouts, arenas, and `Linker.canonicalLayouts()` for platform-dependent C types. COM vtable dispatch is trivially expressible (read function pointer, call with `this`). Zero native build infrastructure. |
| JNI + hand-written C++ glue | ❌ | Requires compiling/shipping our own native library for 6 OS×arch combos; exactly the burden this project exists to avoid. |
| JNA | ❌ | Reflection-based marshaling overhead; no C++/COM story; superseded by FFM. |
| JavaCPP | ❌ | Mature but generates and *compiles* JNI glue per platform — native build burden again, plus a runtime dependency. |
| jextract alone | ❌ as primary | jextract only understands C. Slang's primary API is C++ COM-style interfaces, which jextract cannot express. (We borrow its *patterns* for structs/functions, and our generated code looks deliberately jextract-like.) |
| C shim library (flatten COM → C, then jextract) | ❌ | Again a native artifact to build, sign, and ship per platform. Kept in the back pocket as a fallback if an ABI surprise ever makes direct COM dispatch untenable — §11 explains why we don't expect one. |

The pivotal fact making pure-FFM viable: **Slang's COM ABI is deliberately frozen and
FFM-friendly.** The repository's own contribution rules for `include/` forbid reordering,
inserting, or removing virtual methods (append-only vtables), forbid enum renumbering, and version
every descriptor struct with a leading `structureSize` field. A binding generated against v2026.13
therefore keeps working against any newer library; only *new* methods need regeneration.

---

## 3. The native surface we are binding (survey results)

### 3.1 Shipped libraries

Official release archives (`slang-<ver>-<os>-<arch>.zip`, produced by cpack in
`.github/workflows/release.yml`) contain in `lib/`:

| Library | Windows | Linux | macOS | Needed by us |
|---|---|---|---|---|
| Compiler core | `slang-compiler.dll` | `libslang-compiler.so` | `libslang-compiler.0.<ver>.dylib` (+`libslang-compiler.dylib` symlink) | **Yes — the library we bind** |
| glslang bridge | `slang-glslang.dll` | `libslang-glslang.so` | `libslang-glslang-<ver>.dylib` | Yes (dlopened by slang for SPIR-V optimization/validation paths) |
| GLSL compat module | `slang-glsl-module.*` | 〃 | 〃 | Yes if GLSL-syntax modules are imported; cheap, bundle it |
| LLVM backend | `slang-llvm.*` | 〃 | 〃 | Optional (CPU/host-callable targets); large → separate optional artifact |
| Runtime | `slang-rt.*` | 〃 | 〃 | Not needed for compilation; only for hosting CPU-compiled shaders |
| gfx | `gfx.*` | 〃 | 〃 | No (out of scope) |
| Standard modules | `*.slang-module` files | 〃 | 〃 | Bundle alongside libs (glsl module etc. load them) |

Notes: the main library was recently renamed `slang` → `slang-compiler`; releases can also ship a
legacy `slang.dll` proxy / `libslang` symlink (CMake option). Our loader tries `slang-compiler`
first, then `slang`. macOS binaries are signed and notarized by Khronos — we must extract them
byte-exact to preserve signatures. Exact per-platform file manifests get pinned (with SHA-256) in
the natives build script during M0.

**Confirmed during M0** (manifests for all six platforms are committed under
`natives/manifests/`): on Windows the loadable DLLs *and* the `slang-standard-module-*` tree live
under `bin/` — the archive's `lib/` holds only MSVC import libraries — so the natives task
normalizes every runtime file into a single `lib/` payload directory per platform (safe because
slang-compiler locates companions relative to its own file). Zip extraction flattens the posix
library symlinks into tiny text stubs (`libslang-compiler.dylib` → 33 bytes of ASCII); the
natives task re-materializes them as real symlinks, and the loader independently rejects
implausibly small "library" files so user-extracted zips can't trip it either.

### 3.2 C entry points (`extern "C"` exports in slang.h)

22 exports; the ones we care about:

- `SlangResult slang_createGlobalSession2(const SlangGlobalSessionDesc*, slang::IGlobalSession**)`
  — the front door. `SlangGlobalSessionDesc` is `structureSize`/`apiVersion`-versioned
  (`SLANG_API_VERSION` is currently `0`).
- `const char* spGetBuildTagString()` — version probe, used by the loader for compatibility checks.
- `ISlangBlob* slang_createBlob(const void*, size_t)` — native-owned blob from caller bytes.
- `slang_loadModuleFromSource` / `slang_loadModuleFromIRBlob` / `slang_loadModuleInfoFromIRBlob`,
  `slang_shutdown`, `slang_getLastInternalErrorMessage`, record/replay toggles.

### 3.3 The reflection API is plain C — bind it as such

The modern C++ reflection classes in `slang.h` (`slang::TypeReflection`,
`slang::TypeLayoutReflection`, `slang::VariableLayoutReflection`, `slang::ProgramLayout`,
`slang::EntryPointReflection`, …) are **header-only inline wrappers**: every method compiles to a
call to an `extern "C"` function (`spReflectionType_GetKind`, `spReflection_getGlobalParamsTypeLayout`,
… — 182 `spReflection*` exports total, declared in `slang-deprecated.h`, which `slang.h` includes).

Consequence: although the *header* is named "deprecated", those C functions **are the live,
supported ABI** — they are what every C++ user of the reflection API actually links against.
For Java this is a gift: the entire reflection surface is ordinary C downcalls, no vtables
involved. Our Java reflection classes mirror the C++ wrapper classes one-to-one, backed by the
same C functions. (Alternative considered and rejected as primary: consuming Slang's reflection
*JSON* output — simpler but lossy, no lazy queries, no `specializeType`/`getTypeLayout` round-trips,
schema less stable than the C ABI.)

### 3.4 COM-style interfaces — the scan

27 interfaces, 150 virtual methods (own methods / full vtable size incl. bases; the M0 regex
scan reported 149 — libclang later found the 150th, `IMetadata::isParameterLocationUsed`, which
is declared without `SLANG_MCALL`; see the M2 status note):

| Interface | Base | Own | Vtable |
|---|---|---:|---:|
| `IGlobalSession` | ISlangUnknown | 30 | 33 |
| `ISession` | ISlangUnknown | 21 | 24 |
| `IComponentType` | ISlangUnknown | 14 | 17 |
| `IModule` | IComponentType | 13 | 30 |
| `IEntryPoint` | IComponentType | 1 | 18 |
| `ITypeConformance` | IComponentType | 0 | 17 |
| `IComponentType2` | ISlangUnknown | 3 | 6 |
| `ISlangBlob` | ISlangUnknown | 2 | 5 |
| `ISlangFileSystem` | ISlangCastable | 1 | 5 |
| `ISlangFileSystemExt` / `ISlangMutableFileSystem` | … | 7 / 4 | 12 / 16 |
| `IMetadata`, `ICompileResult`, coverage/bindless/cooperative metadata, writer, profiler, shared-library, castable, clonable, byte-code runner, … | | | |

Every interface carries a `SLANG_COM_INTERFACE(...)` UUID (for `queryInterface`), and the first
three slots are always `queryInterface` / `addRef` / `release`.

**ABI cleanliness findings** (what makes or breaks FFM-COM):

- **No overloaded virtual methods** anywhere. (MSVC reverses the vtable order of *adjacent
  overloads* relative to Itanium — their absence means one slot table serves all platforms. The
  generator hard-fails if a future header ever introduces one.)
- **No virtual destructors** in any interface (a virtual dtor occupies 1 slot on MSVC but 2 on
  Itanium — would skew everything after it). Generator validates.
- **No struct-by-value returns** from any virtual method (MSVC returns aggregates from *member*
  functions via hidden pointer even when register-sized; Itanium doesn't — the classic
  cross-compiler COM trap). Generator validates. All returns are pointers, `SlangResult`/ints,
  or three benign scalars: `OSPathKind` (`enum : uint8_t`), `SlangBool` (= C++ `bool`, 1 byte),
  and one `long` (`ISlangProfiler::getEntryTimeMS`) — the only platform-dependent-width type in
  the whole vtable surface, handled via `Linker.canonicalLayouts().get("long")`
  (4 bytes on Windows LLP64, 8 on Linux/macOS LP64).
- Struct parameters are passed by pointer/reference only (`SlangUUID const&`,
  `SessionDesc const&` → an address at the ABI level). By-value structs would be fine for *C*
  functions under FFM anyway, but don't occur on the COM surface.
- Calling convention: `SLANG_MCALL` is `__stdcall`, which is meaningless on all 64-bit targets —
  the native linker's default C convention is correct everywhere we support.

### 3.5 Scalar type mapping

| C | Java layout | Note |
|---|---|---|
| `SlangResult` | `JAVA_INT` | HRESULT-style; negative = failure. |
| `SlangInt` / `SlangUInt` | `JAVA_LONG` | int64 on 64-bit builds. |
| `size_t` | `canonicalLayouts().get("size_t")` | 8 bytes on every supported target, but resolve it, don't assume. |
| `long` | `canonicalLayouts().get("long")` | The one true platform variance. |
| `bool` / `SlangBool` | `JAVA_BOOLEAN` | 1 byte. |
| `char*` (UTF-8) | `ADDRESS` + `MemorySegment::getString` / `Arena::allocateFrom` | Slang is UTF-8 throughout. |
| enums (`SlangCompileTarget`, `SlangStage`, …) | width read from clang, not assumed | Emitted as Java enums + raw int constants. |
| `SlangUUID` | 16-byte struct layout | Passed by `const&` ⇒ ADDRESS. |

Descriptor structs to lay out in M1: `SlangGlobalSessionDesc`, `SessionDesc`, `TargetDesc`,
`PreprocessorMacroDesc`, `CompilerOptionEntry`/`CompilerOptionValue`, `SpecializationArg`. All are
plain data; field offsets come from libclang (never hand-counted — several contain `bool` fields
followed by padding).

---

## 4. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ user code                                                       │
│   try (var slang = Slang.createGlobalSession()) { ... }         │
├─────────────────────────────────────────────────────────────────┤
│ io.github.refux.slang           (hand-written, idiomatic)       │
│   GlobalSession, Session, Module, EntryPoint, ComponentType,    │
│   CompileTarget, ShaderReflection…, SlangException, builders    │
├─────────────────────────────────────────────────────────────────┤
│ io.github.refux.slang.ffi       (GENERATED by slang-bindgen)    │
│   SlangNative      – C downcall handles (slang_*, sp*)          │
│   IGlobalSession…  – vtable-slot dispatch per interface         │
│   SessionDesc…     – MemoryLayouts + accessors                  │
│   SlangTarget…     – enums/constants                            │
│   ComRuntime       – IUnknown plumbing, upcall vtable factory   │
├─────────────────────────────────────────────────────────────────┤
│ io.github.refux.slang.loader    (hand-written)                  │
│   natives extraction, SymbolLookup, version check               │
├─────────────────────────────────────────────────────────────────┤
│ slang-compiler + slang-glslang [+ slang-llvm] native libraries  │
│   (unmodified official Khronos-signed release binaries)         │
└─────────────────────────────────────────────────────────────────┘
```

### Repository layout (Gradle, Kotlin DSL)

```
slang-java/
├── DESIGN.md                        this document
├── settings.gradle.kts
├── slang/                           the published library
│   └── src/main/java/io/github/refux/slang/
│       ├── ...                      idiomatic layer (hand-written)
│       ├── ffi/                     generated layer (committed, reviewed)
│       └── loader/
├── bindgen/
│   ├── extract/extract_api.py       libclang → api/slang-api.json  (dev-time only)
│   └── src/main/java/...            codegen: slang-api.json → ffi/ sources
├── api/
│   ├── slang-api.json               committed API model, pinned to Slang release
│   └── slang-abi.lock               vtable/enum lockfile (append-only enforcement)
├── natives/
│   ├── download.gradle.kts          fetch + verify official release zips
│   └── (windows|linux|macos)-(x86_64|aarch64)/   repackaged jars
├── samples/
│   ├── hello-compile/               source → SPIR-V + reflection dump
│   └── lwjgl-vulkan/                feed generated SPIR-V into LWJGL Vulkan
└── tools/api-scan.py                the prototype scanner used for this document
```

### Published artifacts

- `io.github.refux:slang` — the Java library (JPMS module `io.github.refux.slang`).
- `io.github.refux:slang-natives-{windows,linux,macos}-{x86_64,aarch64}` — one per platform,
  containing the official release `lib/` payload under
  `META-INF/natives/<os>/<arch>/`, plus a manifest with versions and SHA-256s.
- `io.github.refux:slang-natives-llvm-…` — optional, the big `slang-llvm` library for CPU targets.

(Group id `io.github.refux` — the owner's GitHub-user namespace, verifiable on Maven Central
through the GitHub account without owning a domain. Decided 2026-07-13.)

Generated sources are **committed**, not produced on every build: consumers of the repo need no
Python/libclang, diffs of regenerated code are review artifacts on Slang upgrades, and IDEs work
without extra steps.

---

## 5. COM dispatch in pure Java (the core mechanism)

A COM object pointer is a pointer to a struct whose first field is the vtable pointer; the vtable
is an array of function pointers in declaration order, base class first. Dispatch is therefore:

```java
// Generated once per interface — real slot numbers from the v2026.13 scan:
// IComponentType vtable: [0..2]=IUnknown, [3]=getSession, [4]=getLayout,
//                        [5]=getSpecializationParamCount, [6]=getEntryPointCode, ...
public final class IComponentType extends IUnknown {
    private static final int VT_GET_ENTRY_POINT_CODE = 6;

    // Unbound handle: first arg is the function address, then the C signature.
    // SlangResult getEntryPointCode(SlangInt epIndex, SlangInt targetIndex,
    //                               ISlangBlob** outCode, ISlangBlob** outDiag)
    private static final MethodHandle MH_getEntryPointCode =
        Linker.nativeLinker().downcallHandle(FunctionDescriptor.of(
            JAVA_INT, ADDRESS /*this*/, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));

    public int getEntryPointCode(long epIndex, long targetIndex,
                                 MemorySegment outCode, MemorySegment outDiag) {
        try {
            return (int) MH_getEntryPointCode.invokeExact(
                fnPtr(VT_GET_ENTRY_POINT_CODE), segment(), epIndex, targetIndex, outCode, outDiag);
        } catch (Throwable t) { throw wrap(t); }
    }
}

// In IUnknown (shared base):
protected MemorySegment fnPtr(int slot) {
    MemorySegment vtbl = segment().get(ADDRESS, 0)                    // *this = vtable ptr
        .reinterpret((slot + 1) * ADDRESS.byteSize());                // widen zero-length segment
    return vtbl.getAtIndex(ADDRESS, slot);
}
```

Details that matter:

- **Zero-length segments.** Pointers read from native memory arrive as zero-length
  `MemorySegment`s; wrappers `reinterpret` to the needed size exactly once, at a documented spot.
- **Handle caching.** All `MethodHandle`s are `static final` (JIT constant-folds them). Distinct
  methods sharing a `FunctionDescriptor` share one handle. When `StableValue` (JEP 502 line)
  finalizes, lazy initialization migrates to it; until then, class-init semantics suffice.
- **`invokeExact` only**, with exact carrier types — no boxing on hot paths.
- **`queryInterface`** uses generated 16-byte UUID segments allocated once in the global arena.
- The three IUnknown slots are hand-written in one base class; everything above them is generated.

### Lifetime and `addRef`/`release`

Native objects are refcounted; Java wrappers own exactly one reference:

- Every wrapper is `AutoCloseable`; `close()` calls `release()` and poisons the wrapper.
  Idiomatic usage is try-with-resources.
- A `Cleaner` (one per library instance) is the safety net for unreachable un-closed wrappers —
  it releases and, in `-Dio.github.refux.slang.debug=true` mode, logs the allocation-site stack
  trace of the leaked wrapper.
- Reflection pointers (`TypeReflection*` etc.) are **not** refcounted — they are owned by their
  session/program. Java reflection wrappers hold the raw pointer plus a strong reference to the
  owning wrapper, so the owner cannot be released (or GC-cleaned) while reflection objects are
  reachable. `Reference.reachabilityFence` guards the call windows.
- Out-param temporaries, marshalled strings, and descriptor structs live in per-call
  `Arena.ofConfined()`. Blob payloads are copied to `byte[]` by default, with a zero-copy
  `MemorySegment` view for consumers (LWJGL) that want to hand the bytes straight to Vulkan.

---

## 6. Upcalls: implementing Slang interfaces *in* Java

Required for `ISlangFileSystem` (virtual `import`/`#include` resolution — the main integration
point for engines with pack files) and `ISlangBlob` (returning file contents). Mechanism,
generated per interface:

1. One **static vtable segment** per implemented interface: an array of upcall stubs
   (`Linker.upcallStub`) created once in the global arena; stubs dispatch to Java through an
   instance registry.
2. Each Java-implemented COM object = a small native allocation
   `[ vtable* | int64 instanceId ]` in an `Arena.ofShared()` (upcalls may arrive on any native
   thread), plus a registry entry `instanceId → Java object`.
3. `addRef`/`release` maintain an `AtomicInteger` on the Java side; on zero, the registry entry
   and the native allocation are freed. `queryInterface` answers for the interface chain's UUIDs
   and `ISlangUnknown`, honoring COM identity rules.
4. `ISlangCastable.castAs` (base of `ISlangFileSystem`) returns null — documented contract.

A built-in `MapFileSystem` (path → bytes) and a `Path`-based file system ship in the idiomatic
layer, doubling as the reference implementations and upcall stress tests.

---

## 7. Loading and packaging natives

Resolution order (first hit wins), all funneling into `SymbolLookup.libraryLookup`:

1. `-Dio.github.refux.slang.libraryPath=/dir` (or env `SLANG_JAVA_LIBRARY_PATH`) — a directory
   containing the full lib set; supports developers testing a local Slang build.
2. Natives jar on the classpath/modulepath: extract **the entire lib payload** to a per-version
   cache directory (`~/.cache/slang-java/<slangVersion>-<payloadHash>/`), reusing it if the hash
   matches. Extracting the full set matters because `slang-compiler` locates `slang-glslang` /
   `slang-llvm` / `slang-glsl-module` and `*.slang-module` files **relative to its own path**.
3. Bare `libraryLookup("slang-compiler", …)` → system default search, then legacy name `slang`.

Startup validation: call `spGetBuildTagString` and compare against the pinned version — newer than
pinned is allowed (append-only ABI), older fails fast with a clear message. As an internal
consistency check, M0 also calls the *vtable* path `IGlobalSession::getBuildTagString` (slot 8)
and asserts it matches the C-export answer — a one-line proof the COM slot math is right on every
platform.

Platform notes:

- **macOS**: copy byte-exact to preserve the Khronos code signature; JVM-created files carry no
  quarantine attribute, so Gatekeeper is not triggered. Load the versioned dylib name from the
  manifest (the `libslang-compiler.dylib` symlink doesn't survive zip/jar packaging).
- **Windows**: load `slang-compiler.dll` by absolute path; its delay-loaded companions resolve
  from the same directory. Long-path-safe cache dir.
- **Linux**: glibc floor is inherited from Slang's own release builders (manylinux-era images);
  document the observed floor in the natives README per release.

---

## 8. The idiomatic layer (what users actually write)

*(Implemented in M3 — the sample below runs verbatim as
`IdiomaticApiTest.designDocSampleRunsAsWritten`.)*

```java
import io.github.refux.slang.*;

try (GlobalSession global = Slang.createGlobalSession();
     Session session = global.newSession()
         .target(CompileTarget.SPIRV, t -> t.profile("spirv_1_5"))
         .searchPath(Path.of("shaders"))
         .define("USE_FOG", "1")
         .create()) {

    Module module = session.loadModuleFromSource("hello", """
        [shader("compute")] [numthreads(8,8,1)]
        void main(uint3 tid : SV_DispatchThreadID) { }
        """);                                  // throws SlangCompileException w/ diagnostics

    try (ComponentType linked = session.composite(module, module.entryPoint("main")).link()) {
        byte[] spirv = linked.entryPointCode(0, 0);        // or .entryPointCodeSegment(...)
        ShaderReflection refl = linked.layout(0);
        for (var param : refl.parameters())
            System.out.println(param.name() + " : " + param.category());
    }
}
```

Conventions:

- Failed `SlangResult` + diagnostics blob → `SlangCompileException` (message = diagnostics text);
  non-compile failures → `SlangException` with decoded facility/code.
- Warnings surface via an optional per-session diagnostics consumer, not stdout.
- Enums generated from the header (`CompileTarget.SPIRV`, `Stage.COMPUTE`, …) with `int value()`
  and `of(int)` escape hatches; unknown future values round-trip without crashing.
- The generated `ffi` layer stays public (an "unsafe" escape hatch) but is documented as
  ABI-of-the-header, not semver-stable.

---

## 9. slang-bindgen (the generator tool)

Two stages, deliberately decoupled by a committed JSON model:

**Stage A — extract** (`bindgen/extract/extract_api.py`, Python + libclang; run only when bumping
the Slang version). Parses `slang.h` from the pinned release with libclang and emits
`api/slang-api.json`:

```jsonc
{ "slangVersion": "2026.13",
  "enums":      [{ "name": "SlangCompileTarget", "cWidth": 4,
                   "values": [{"name": "SLANG_SPIRV", "value": 28, "doc": "…"}] }],
  "structs":    [{ "name": "SessionDesc", "size": 96,
                   "fields": [{"name": "structureSize", "type": "size_t", "offset": 0}, …] }],
  "interfaces": [{ "name": "IComponentType", "base": "ISlangUnknown",
                   "uuid": "5bc42be8-5c50-4929-9e5e-d15e7c24015f",
                   "methods": [{ "slot": 6, "name": "getEntryPointCode", "ret": "SlangResult",
                                 "params": [{"name":"entryPointIndex","type":"SlangInt"}, …],
                                 "doc": "…" }] }],
  "functions":  [{ "name": "slang_createGlobalSession2", "ret": "SlangResult", "params": […] }],
  "reflectionWrappers": [{ "class": "TypeReflection", "method": "getKind",
                           "callee": "spReflectionType_GetKind" }] }
```

The last section is extracted from the inline bodies of the C++ reflection wrapper classes, so the
Java reflection layer's mapping to `spReflection*` functions is *derived*, not hand-maintained.

Extractor **validations** (hard failures — each one is a cross-ABI hazard from §3.4):
overloaded virtuals; virtual destructors; struct-by-value virtual returns; missing UUIDs; unknown
types; and **layout divergence** — the extractor runs clang for all six target triples
(`x86_64|aarch64` × `windows-msvc|linux-gnu|apple-macos`) and requires identical struct
offsets/sizes except where a canonical layout (`long`) explains the difference.

It also maintains `api/slang-abi.lock`: the flattened vtable order, enum values, and struct
offsets of every previously published binding. Regeneration diffs against it and **fails on any
non-append change** — mechanically enforcing on our side the same append-only contract the Slang
repo promises on theirs.

**Stage B — codegen** (`bindgen/src/main/java`, plain Java, no dependencies beyond the JDK; runs
as a Gradle task reading only the JSON). Emits the whole `io.github.refux.slang.ffi` package:
`SlangNative` (C downcalls), interface classes with slot dispatch, struct layout classes with
typed accessors, enums, UUID table, and upcall vtable factories — with javadoc carried over from
the header comments.

**Prototype status:** [`tools/api-scan.py`](tools/api-scan.py) (regex-based) already produces the
interface inventory, vtable ordering, UUIDs, ABI-hazard flags, and export lists used throughout
this document. It is the seed and the spec for Stage A, but the production extractor must be
libclang-based — regex parsing is fine for a survey, not for slot-perfect codegen.

---

## 10. Java feature policy

- **Baseline: JDK 25, the current LTS** (decision 2026-07-13, revised from the original JDK 26
  plan: the binding uses nothing newer than the JDK 22 FFM API, so the baseline follows the
  newest LTS for consumer reach; it gets bumped only when a feature we actually use requires it).
  `-PjavaLanguageVersion=26` builds/tests on a newer toolchain for forward-compatibility checks.
- **Toolchain:** JDK 25 in CI and for release builds (Gradle toolchains + foojay resolver).
- **Published artifacts use finalized features only.** Classfiles compiled with
  `--enable-preview` run *only* on that exact JDK feature version — unacceptable for a library.
  Core relies on: FFM (final, 22), records/sealed/patterns, module imports & flexible constructor
  bodies (final, 25), `Cleaner`, `MethodHandles`.
- **Preview/newer-JDK features are welcome where they don't leak:** samples, tests, and bindgen
  may use Structured Concurrency (parallel multi-target compilation demo), Scoped Values, Stable
  Values (JEP 502 line) for handle caches, compact source files for sample `main`s. Each usage is
  annotated with its JEP so promotion-to-final cleanups are greppable.
- **Native access (JEP 472):** consumers need `--enable-native-access=io.github.refux.slang`
  (module path) or `ALL-UNNAMED` (classpath); executable-jar users get the
  `Enable-Native-Access` manifest attribute. README documents all three, since JDK is moving
  illegal native access from warn to deny.
- `Linker.Option.critical` for hot no-callback reflection getters is a *measured*, opt-in M6
  experiment — never on paths that can block or upcall.

---

## 11. Threading model

- `IGlobalSession` creation is expensive (core module load); create once, share.
- Slang does not internally synchronize compilation on one session: the documented-safe pattern is
  **one `Session` (and its modules/component types) confined to one thread at a time**; different
  sessions may run on different threads concurrently. The Java layer documents this, asserts
  optional confinement in debug mode, and the samples show a session-per-worker pattern
  (Structured Concurrency demo compiles N entry points on N sessions).
- Upcalls (file system) arrive synchronously on the compiling thread; implementations must be
  reentrant-safe if the same FS instance serves several sessions. All upcall-visible native memory
  uses shared arenas.

## 12. Testing & CI

- **No GPU anywhere.** Golden tests compile to text targets (HLSL/GLSL/WGSL contain expected
  substrings) and to SPIR-V (magic number `0x07230203`, plus `spirv-dis`-free structural checks).
  This mirrors how the slang repo itself tests CPU-only.
- Unit tests: loader resolution order, version gate, wrapper refcount/leak detector (Cleaner
  instrumentation), upcall file system (including refcount stress across many compiles), UTF-8
  round-trips, enum round-trips of unknown values.
- Reflection tests: known shader → assert full parameter/type-layout tree.
- **Matrix:** `{ubuntu-latest, ubuntu-24.04-arm, windows-latest, macos-15, macos-15-intel}`
  × JDK 25 (Intel mac best-effort). windows-aarch64 is descoped from CI for now (owner call,
  2026-07-13): Temurin publishes no JDK 25 for windows-arm, so `setup-java` fails before any
  test runs; Azul Zulu does ship `win_aarch64` JDK 25 builds if the platform is ever wanted.
  Natives manifests still cover it, so only the CI job is missing, not distribution support.
- **ABI-drift canary** (scheduled weekly): run the extractor against slang `master`'s headers and
  diff the model — early warning that a new method/enum appeared (benign, plan regen) or that a
  validation tripped (escalate upstream while it's still cheap to fix — the slang repo treats
  vtable edits as reviewable mistakes).

## 13. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Hidden C++ ABI divergence (vtable slots) between MSVC and Itanium for this header | Low — no overloads, no virtual dtors, no aggregate returns (scanned; generator enforces forever) | M0 smoke test cross-checks a COM call against a C export on all platforms; C-shim fallback documented in §2 |
| Slang breaks its append-only ABI promise | Low (policy is written into their contributor docs) | `slang-abi.lock` + weekly canary catches it pre-release; pin exact natives |
| Library renames / packaging churn (e.g. `slang` → `slang-compiler`) | Medium (just happened) | Loader name fallback list; natives manifest pins exact filenames + hashes per release |
| `SessionDesc` lifetime semantics | *resolved in M1* | `createSession` **copies** the descriptor contents: `CompilePipelineTest.createSessionCopiesDescriptorContents` clobbers a macro string right after the call and the session keeps the original value. Descriptor arenas may close as soon as the call returns (encoded in the `SessionDesc` javadoc) |
| Baseline too new for some consumers | Low | Code needs nothing newer than JDK 22 FFM; lowering the baseline below 25 is a one-line toolchain change if users ask |
| Preview-classfile lock-in | — | Policy §10: preview never ships in published jars |
| macOS Gatekeeper on extracted dylibs | Low | Byte-exact extraction preserves notarized signatures; JVM writes carry no quarantine attr; CI runs on clean macOS runners to catch regressions |
| Large natives (slang-llvm ~100s of MB) bloating downloads | Certain | Separate optional artifact; core natives stay lean |

## 14. Prior art

- **LWJGL** — the natives-classifier-jar + extract-to-cache loader pattern we copy; it binds
  shaderc and SPIRV-Cross but has no Slang binding (verified 2026-07). The `lwjgl-vulkan` sample
  doubles as the integration proof.
- **jextract** — API style reference for the generated layer (layouts, typed accessors).
- **slangpy / slang-wasm** — Slang's own non-C++ bindings; useful precedents for API *shape*
  (what to expose eagerly vs lazily), not mechanism.
- No existing maintained Java binding for Slang was found (web search, 2026-07) — greenfield.

## 15. Open questions

1. **Maven coordinates / namespace** — *resolved 2026-07-13*: `io.github.refux` (the owner's
   GitHub-user namespace, verifiable on Maven Central via a temporary public repo instead of
   domain ownership). Group id, Java packages (`io.github.refux.slang[.ffi|.loader]`), the future
   JPMS module name, and the loader property `io.github.refux.slang.libraryPath` all use it.
2. Bind `IByteCodeRunner` (interpreter) and record/replay toggles in v1, or keep behind the
   experimental flag? (Generator emits them either way; cost is test surface.)
3. Host-callable CPU execution from Java (compile Slang → native code → call it *back* via FFM —
   needs `slang-llvm` + `slang-rt`): flashy demo, real users? Parked as M6 stretch.
4. Should the high-level reflection layer be lazy-tree or eager-snapshot? Leaning lazy (mirrors
   C++ API, cheap), with an eager `toJson()` escape hatch via Slang's reflection-JSON.

---

## 16. Implementation plan

Milestones are strictly ordered by risk retired; each has a hard exit criterion. Sizes:
S ≈ a day, M ≈ 2–4 days, L ≈ 1–2 weeks of focused work.

### M0 — Walking skeleton: prove FFM↔COM on all three OSes (M)
- Gradle multi-project scaffold, JDK toolchain via foojay (see §10 for the baseline), Spotless,
  JUnit 6.
- `natives/download.gradle.kts`: fetch pinned v2026.13 release zips per platform, record SHA-256s
  and the per-platform lib manifest (answers §3.1's "exact file list" question).
- Hand-written micro-binding (no generator yet): loader + `spGetBuildTagString` +
  `slang_createGlobalSession2` + vtable call `IGlobalSession::getBuildTagString` (slot 8) +
  `release` (slot 2).
- GitHub Actions matrix (see §12 for the platform set) runs it.
- **Exit:** CI prints matching build tags from the C path and the COM path on every platform.
- **Status (2026-07-13): complete.** Smoke tests verified locally on macos-aarch64 against both
  a local Slang build and the official v2026.13 binaries (first on a foojay-provisioned JDK
  26.0.1, re-verified on JDK 25 when the baseline was revised to the LTS, §10). Pushed to
  github.com/RefuX/slang-java; CI matrix green on linux-x86_64, linux-aarch64, windows-x86_64,
  macos-aarch64, and macos-x86_64. windows-aarch64 descoped from CI (owner call — §12).
  Deviation: Spotless deferred to M1.

### M1 — Hand-written compile pipeline: validate ergonomics & lifetimes (M)
- Hand-write minimal `SessionDesc`/`TargetDesc` layouts and the
  `createSession → loadModuleFromSourceString (slot 20) → findEntryPointByName →
  createCompositeComponentType (slot 6) → link (slot 10) → getEntryPointCode (slot 6)` path.
- Diagnostics blobs → exceptions; first golden tests (SPIR-V magic, HLSL substring).
- Empirically settle the `SessionDesc` copy-semantics question; encode the answer in a lifetime
  test and a doc note.
- **Exit:** `hello-compile` golden test green on the full matrix; leak detector clean.
- **Status (2026-07-13): complete — CI matrix green on all five platforms.** Descriptor layouts
  hand-written against compiler-verified numbers from `tools/abi-probe.cpp` (TargetDesc 48 B —
  where the probe caught a non-obvious default, `kDefaultTargetFlags` = 1024; SessionDesc 96 B).
  Full pipeline green in `CompilePipelineTest` on linux-x86_64/aarch64, windows-x86_64, and
  macos-aarch64/x86_64: SPIR-V magic, HLSL text, multi-target index mapping, diagnostics as
  `SlangCompileException` (and the session stays usable after a failed compile). Copy semantics
  answered — `createSession` copies (risk row above). Spotless (palantir-java-format) added,
  clearing the M0 deferral. "Leak detector clean" is interpreted for M1 as deterministic
  close discipline in every test path; the Cleaner-based detector lands in M3 as designed.

### M2 — slang-bindgen (L)
- Stage A extractor (Python + libclang): full model JSON + six-triple layout validation +
  `slang-abi.lock`. Stage B codegen (Java): emit the complete `ffi` package — 27 interfaces,
  enums, structs, C exports (modern set + `spReflection*`; deprecated compile-request excluded;
  experimental interfaces flagged).
- Swap M1's hand-written internals for generated code **without changing M1's tests**.
- **Exit:** M1 tests pass purely on generated bindings; regen is one documented command; lockfile
  committed.
- **Status (2026-07-13): complete — CI matrix green on all five platforms** (linux-x86_64/
  aarch64, windows-x86_64, macos-aarch64/x86_64 all passed the full suite on the generated
  bindings). Stage A (`bindgen/extract/extract_api.py`, Python +
  libclang from the host Xcode toolchain, repo-local venv) parses the real slang.h in C++ mode,
  validates struct layouts / vtable order / enum values byte-identical across all six target
  triples (non-host triples parse freestanding with two ABI-irrelevant header shims), and
  enforces the append-only `api/slang-abi.lock` (1,114 facts; `--reset-lock` exists only for
  lock-format migrations). Stage B (`:bindgen:run`, dependency-free Java) emits 106 files:
  FfiSupport, 55 enum classes, 21 struct layout classes (offset-based accessors, so
  `SpecializationArg`'s union members simply overlap), 27 interfaces with static jextract-style
  dispatch taking `self`, and 195 C downcalls with per-function lazy holders. The hand-written
  `ffi` classes became thin veneers over `ffi.gen`; the M0+M1 test files ran unchanged and
  green, and a new `GeneratedBindingsTest` drives generated-only surface (`getLayout` slot 4 +
  `spReflection_GetParameterCount`). Regeneration is deterministic (byte-identical re-runs) and
  documented in CLAUDE.md.
  **Findings the generator surfaced:** the true vtable method count is 150 (§3.4 note) because
  `IMetadata::isParameterLocationUsed` is declared without `SLANG_NO_THROW`/`SLANG_MCALL`
  (benign on 64-bit targets, worth an upstream header fix); seven
  `spReflectionTypeLayout_getSubObjectRange*` declarations sit inside `#if 0` and are correctly
  absent from the model (the M0 regex had counted them); and two functions are declared in
  slang.h but **not exported** by the official v2026.13 library (`slang_getEmbeddedCoreModule`,
  `spReflection_GetSession`) — the concrete reason function handles bind lazily.

### M3 — Idiomatic core API (M)
- Hand-written layer of §8: sessions/modules/linking/target code, exceptions, enums, builders,
  Cleaner safety net + debug leak tracing, thread-confinement asserts.
- **Exit:** sample code in §8 compiles and runs as written; API review pass done.
- **Status (2026-07-13): complete — CI matrix green on all five platforms.** The §8 sample runs
  **verbatim** as `IdiomaticApiTest.designDocSampleRunsAsWritten`. Public surface:
  `Slang.createGlobalSession()`, `GlobalSession`/`SessionBuilder`/`TargetOptions`,
  `Session` (module loading with warnings→consumer, composition, debug thread-confinement
  asserts), `ComponentType`/`Module`/`EntryPoint`, `CompileTarget` + `ParameterCategory` enums
  (`value()`/`of(int)` escape hatches, unmapped → `UNKNOWN`), and `ShaderReflection` as an
  eager parameter snapshot (M4 brings the lazy tree). Lifecycle: every wrapper is
  `NativeObject` — explicit `close()` is idempotent/thread-safe via `Cleaner.Cleanable`, an
  unclosed wrapper is released by the Cleaner when unreachable (so the sample's inline
  `module.entryPoint("main")` is safe by design), and `-Dio.github.refux.slang.debug=true`
  (or `-PslangDebug` for the test task) logs allocation-site leak traces and enforces session
  thread confinement. The full suite (15 tests) passes in normal and debug mode. Deferred with
  intent: `entryPointCodeSegment` zero-copy variant (needs blob-lifetime design, M4/M6),
  `Stage` enum and module `name()` accessors (M4 reflection), generated idiomatic enums
  (the two hand-written ones become the template).

### M4 — Reflection (L)
- Generate raw `spReflection*` bindings + the derived wrapper mapping (§9
  `reflectionWrappers`); hand-polish the public reflection classes (naming, iterators,
  null-object conventions).
- Reflection golden tests (parameter blocks, generics/specialization, entry-point layouts).
- **Exit:** the user-guide reflection walkthrough is reproducible from Java.
- **Status (2026-07-13): complete.** The generator now emits one instance class per C++
  reflection wrapper class into `io.github.refux.slang.gen` (11 classes, 166 methods — same
  generated-code-lives-in-a-`.gen`-package convention as `ffi`/`ffi.gen`) straight from the
  model's `reflectionWrappers` mapping: methods forward to the `SlangReflectionAPI` downcall the
  C++ inline wrapper compiles to, reflection pointers come back typed via a self-derived
  pointee→class table, `const char*` becomes String, C++ overloads become Java overloads, null
  wrapper arguments pass as NULL pointers (default-arg conveniences rely on it), and every
  wrapper threads an `owner` reference keeping the native data's owner reachable. Hand-written
  veneers in the parent package (same simple name, extending the generated base) add ergonomics: `name()`/`kind()`/`category()`/`stage()` conveniences,
  lazy `List` views (`fields()`, `parameters()`, `entryPoints()`), byte-unit `size()`/`stride()`
  /`offset()` defaults matching the C++ default arguments, `computeThreadGroupSize()` as
  `long[]`, and `ShaderReflection.toJson()` over Slang's reflection-JSON emitter. New idiomatic
  enums: `Stage`, `TypeKind`. M3's eager snapshot was replaced by this lazy tree with
  `parameters()` shape-compatible (`VariableLayoutReflection.name()/category()`).
  **Exit criterion met:** `ReflectionWalkthroughTest` reproduces the user-guide chapter —
  parameter names/categories/bindings, cbuffer packing offsets (0/16/28/32, struct size 96),
  vector/matrix shapes, entry-point stage + `[numthreads(8,4,2)]`, cross-checked against
  `spReflection_ToJson` output. Full suite: 20 tests, green in normal and debug mode.
  Deferred: generics/specialization golden tests ride with the specialize() API surface (M6
  stretch); DeclReflection/FunctionReflection/GenericReflection veneers are shells until needed.

### M5 — Upcalls: Java file systems (M)
- Generic upcall COM factory + `ISlangBlob`/`ISlangFileSystem` support, `MapFileSystem`,
  `PathFileSystem`; refcount stress tests; shared-arena audit.
- **Exit:** compile a multi-file module graph served entirely from a Java `Map<String,String>`.

### M6 — Distribution & polish (M)
- Natives publishing pipeline (download→verify→repackage→sign?→publish), Maven Central setup,
  javadoc, README quickstart incl. `--enable-native-access` guidance, LWJGL Vulkan sample,
  version-compatibility doc (pinned vs newer natives), optional perf pass
  (`Linker.Option.critical`, StableValue), weekly ABI canary workflow.
- **Exit:** `mvn`/Gradle consumers on all three OSes can compile a shader with two dependencies
  and zero manual native setup.

### Suggested first PR stack
1. repo scaffold + CI skeleton (M0a) → 2. natives download + manifests (M0b) → 3. micro-binding +
smoke test (M0c) → then M1 as one PR per bullet.

---

## Appendix A — key vtable slots (v2026.13, from `tools/api-scan.py`)

```
IGlobalSession  [3] createSession   [4] findProfile   [8] getBuildTagString  … (33 slots)
ISession        [4] loadModule      [5] loadModuleFromSource
                [6] createCompositeComponentType      [20] loadModuleFromSourceString  (24 slots)
IComponentType  [3] getSession  [4] getLayout  [6] getEntryPointCode  [10] link  (17 slots)
IModule = IComponentType + [17] findEntryPointByName … (30 slots)
ISlangBlob      [3] getBufferPointer  [4] getBufferSize  (5 slots)
```
(Illustrative excerpts; the generator, not this table, is the source of truth.)

## Appendix B — facts checklist for reviewers

- Reflection C ABI lives in `slang-deprecated.h` but is the live surface (included by slang.h;
  the C++ wrappers inline to it). Verified against `source/slang/slang-reflection-api.cpp`.
- Library rename `slang` → `slang-compiler` with legacy proxy outputs: `CMakeLists.txt:169`,
  `source/slang/CMakeLists.txt` (`OUTPUT_NAME slang-compiler`), release.yml signing list.
- Append-only vtable / enum rules: slang repo `CLAUDE.md` §"Modifying Public Headers".
- Scan numbers (27 interfaces / 149 methods / 3 scalar-return oddities / 0 overloads / 0 virtual
  dtors / 0 aggregate returns): `tools/api-scan.py` output, 2026-07-13.

# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Hard rules

- **Never `rm`, `mv`, or `git push` without asking James for permission first.** This includes
  the routine-looking cases: deleting files so a task regenerates them, renaming or moving
  source trees, and pushing follow-up or docs-only commits. Local `git commit` is fine;
  anything that deletes, relocates, or publishes is not — ask, with a one-line what/why.

## Project

Java bindings for the Slang shader compiler built on FFM — pure Java, no JNI, binding the
official Khronos-signed release binaries directly. The design document and M0–M6 milestone plan
live in [DESIGN.md](DESIGN.md); per-milestone status is recorded inline there. Current state:
M0 (walking skeleton) and M1 (hand-written compile pipeline) complete with the CI matrix green;
M2 (the slang-bindgen generator) is next.

## Commands

- Fetch the pinned Slang natives: `./gradlew :natives:downloadNatives`
  (`-PnativesPlatform=all` for every platform; downloads verify against `natives/manifests/`)
- Run tests: `./gradlew :slang:test -PslangNativesDir=natives/build/payload/<os-arch>/lib`
  (or set `SLANG_JAVA_LIBRARY_PATH` to any Slang build's lib directory, e.g. a local
  slang checkout's `build/Release/lib`)
- Format before committing: `./gradlew :slang:spotlessApply` (palantir-java-format; generated
  `ffi/gen/**` is excluded — the generator's output is the canonical formatting)
- Regenerate the bindings (e.g. after a Slang version bump) — two commands, then rerun tests:
  1. `bindgen/extract/.venv/bin/python bindgen/extract/extract_api.py --slang-include <slang-repo>/include --slang-version <ver> --out api/slang-api.json --lock api/slang-abi.lock`
  2. `./gradlew :bindgen:run --args="api/slang-api.json slang/src/main/java"`
  One-time venv setup: `python3 -m venv bindgen/extract/.venv && bindgen/extract/.venv/bin/pip install libclang`.
  `api/slang-abi.lock` is append-only ABI enforcement; `--reset-lock` is only for lock-format
  migrations and must be justified in the commit message.
- Verify hand-written struct layouts: `tools/abi-probe.cpp` (build/run instructions in its header)

## Conventions

- JDK 25 LTS baseline, toolchain auto-provisioned via foojay; no preview features in published
  code (preview classfiles only run on their exact JDK version).
- Namespace everywhere is `io.github.refux` — Maven group id, Java packages, and the loader
  property `io.github.refux.slang.libraryPath`.
- The Slang release is pinned as `slangVersion` in `natives/build.gradle.kts`. The manifests
  under `natives/manifests/` are trust-on-first-use SHA-256 records: CI verifies against them,
  and regenerating them means deleting files — which falls under the hard rule above.
- Vtable slot numbers and ABI facts in the hand-written `ffi` layer come from
  `tools/api-scan.py` and `tools/abi-probe.cpp`, never from guessing; cite the source in a
  comment when adding one.

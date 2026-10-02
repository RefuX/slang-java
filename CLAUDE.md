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
M0–M5 complete (walking skeleton, compile pipeline, slang-bindgen generated bindings, idiomatic
core API with the DESIGN.md §8 sample verbatim, the lazy reflection tree with the user-guide
walkthrough as its acceptance test, and Java-implemented COM upcalls serving imports from
`SlangFileSystem`); the remaining milestone is M6 (Maven Central distribution and polish).

## Commands

- Fetch the pinned Slang natives: `./gradlew :natives:downloadNatives`
  (`-PnativesPlatform=all` for every platform; downloads verify against `natives/manifests/`)
- Run tests: `./gradlew :slang:test -PslangNativesDir=natives/build/payload/<os-arch>/lib`
  (or set `SLANG_JAVA_LIBRARY_PATH` to any Slang build's lib directory, e.g. a local
  slang checkout's `build/Release/lib`)
- Format before committing: `./gradlew :slang:spotlessApply` (palantir-java-format; generated
  code is excluded — the generator's output is the canonical formatting). Convention: generated
  code always lives in a `.gen` package (`ffi/gen` for raw dispatch, `gen` for the reflection
  wrapper bases); hand-written veneers live in the parent package.
- Bump to a new Slang release: run the **Slang version bump** workflow
  (`.github/workflows/slang-bump.yml`) from the Actions tab — optional `slang_release` tag input,
  blank takes the latest. It re-pins the natives, re-records all six manifests, regenerates the
  ABI model/lock and the Java, tests every platform, and pushes `bump/slang-<version>` only once
  all four are green; you open the PR (a branch pushed by CI is silent, and anything the default
  token opens arrives with no CI). It runs on macOS because that is the only host that can
  cross-validate all six triples and reproduce the model's own `arm64-apple-macosx` primary
  triple. To do it by hand instead, or to understand what that workflow runs:
  1. `bindgen/extract/.venv/bin/python bindgen/extract/extract_api.py --slang-include <slang-repo>/include --slang-version <ver> --out api/slang-api.json --lock api/slang-abi.lock`
  2. `./gradlew :bindgen:run --args="api/slang-api.json slang/src/main/java"`
  One-time venv setup: `python3 -m venv bindgen/extract/.venv && bindgen/extract/.venv/bin/pip install libclang`.
  `api/slang-abi.lock` is append-only ABI enforcement; `--reset-lock` is only for lock-format
  migrations, or — with James's go-ahead — for rebasing onto a break upstream has already
  shipped (2026.17 repurposed `IGlobalSession` slot 32; see the 2026.19 bump commit). Either way,
  justify it in the commit message and list every lock line it drops. The bump workflow never
  resets, so a bump across an upstream break is done by hand. Struct sizes and derived count
  sentinels (`CountOf`, `SLANG_STAGE_COUNT`, …) are locked monotonically — they may grow, since
  an append moves them by construction — and everything else byte-exact. `python3
  bindgen/extract/test_abi_lock.py` covers that arithmetic and needs no libclang; run it after
  touching `extract_api.py`, and read the `SENTINEL_NAME` comment before widening the rule.
  `bindgen/extract/test_const_eval.py` (venv python, needs libclang) covers global-constant
  folding, which a wrong value would otherwise get frozen into the lock.
- ABI-drift canary: `.github/workflows/abi-canary.yml` runs weekly, parsing Slang upstream
  headers and enforcing the committed lock (`extract_api.py --verify`, which checks the lock
  without rewriting it). A non-append change fails the run; benign additions are reported. Run
  it on demand from the Actions tab (workflow_dispatch, optional `ref` input).
- Release watch: `.github/workflows/slang-release-watch.yml` runs weekly and files one tracking
  issue when upstream ships a release newer than the `slangVersion` pin — the gap the canary
  never covered, since the canary only speaks when the ABI *breaks*. A stale pin keeps one open
  issue, retitled (not re-filed) as newer releases ship, and a bump branch for the latest release
  silences it. Dispatch it with `mode: bump` to run
  the bump workflow from it; that path is off on the schedule until the reusable-workflow
  permission chain has been proven by one real run (rationale in the workflow header).
- Verify hand-written struct layouts: `tools/abi-probe.cpp` (build/run instructions in its header)
- Release: push a tag matching `version` in `gradle.properties` (e.g. `v0.0.1`) — the
  release workflow now refuses to publish if the two disagree —
  `.github/workflows/release.yml` runs the tests against the pinned binaries, publishes
  `io.github.refux:slang-java` to Maven Central (auto-released), and creates the GitHub Release.
  Requires repo secrets `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` and
  `SIGNING_KEY`/`SIGNING_PASSWORD` (same set as slang-wasm-endive). Tagging needs James's
  explicit go-ahead, like any push.

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

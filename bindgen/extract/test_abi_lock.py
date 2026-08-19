#!/usr/bin/env python3
"""Unit tests for the append-only ABI lock's sentinel handling (extract_api.py).

Runs anywhere python3 does: libclang is stubbed out, because nothing under test parses a header
-- these cover the pure lock arithmetic, which is where the interesting mistakes live.

    python3 bindgen/extract/test_abi_lock.py

Background: the weekly ABI-drift canary failed on `enum CompilerOptionName CountOf 156` after
upstream legally appended two options, moving the derived `CountOf` sentinel to 158. The lock now
treats derived count sentinels monotonically. The risk that buys is over-relaxation, so most of
what follows pins down what must STILL be locked byte-exact.
"""

import sys
import tempfile
import types
import unittest
from pathlib import Path

# extract_api imports libclang at module scope purely for the parsing half. Stub it so the lock
# logic is testable on a machine (or CI job) with no clang toolchain installed.
if "clang.cindex" not in sys.modules:
    _clang = types.ModuleType("clang")
    _cindex = types.ModuleType("clang.cindex")
    for _attr in ("TypeKind", "CursorKind", "Config", "Diagnostic", "TokenKind", "Index"):
        setattr(_cindex, _attr, type(_attr, (), {}))
    _clang.cindex = _cindex
    sys.modules["clang"] = _clang
    sys.modules["clang.cindex"] = _cindex

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extract_api  # noqa: E402


def enum(name, values):
    """values: (name, value) or (name, value, "derived")."""
    return {"name": name, "values": [
        {"name": v[0], "value": v[1], **({"derived": True} if len(v) > 2 else {})}
        for v in values]}


# The shapes that actually occur in slang.h, each chosen because it breaks a simpler rule.
MODEL = {"enums": [
    # The failing case: explicit options, one derived terminal sentinel, plus a count-NAMED but
    # explicitly-valued historical marker that upstream froze and must never move.
    enum("CompilerOptionName", [
        ("MacroDefine", 0), ("CountOfParsableOptions", 111),
        ("WarningLevel", 155), ("SeparateDebugInfoOutput", 156), ("DebugInfoIncludeSource", 157),
        ("CountOf", 158, "derived")]),
    # Sentinel declared MID-list, followed by an explicit alias -- so "last enumerator" is wrong.
    enum("SlangStage", [
        ("SLANG_STAGE_NONE", 0), ("SLANG_STAGE_FRAGMENT", 5), ("SLANG_STAGE_NODE", 16),
        ("SLANG_STAGE_COUNT", 17, "derived"), ("SLANG_STAGE_PIXEL", 5)]),
    # Same, and the LAST enumerator is the frozen _COUNT_V1 marker.
    enum("SlangParameterCategory", [
        ("SLANG_PARAMETER_CATEGORY_NONE", 0), ("SLANG_PARAMETER_CATEGORY_SUBPASS", 21),
        ("SLANG_PARAMETER_CATEGORY_METAL_PAYLOAD", 24),
        ("SLANG_PARAMETER_CATEGORY_COUNT", 25, "derived"),
        ("SLANG_PARAMETER_CATEGORY_COUNT_V1", 21)]),
    # X-macro enum: every enumerator is derived. No stable baseline, so nothing may be relaxed.
    enum("SlangImageFormat", [
        ("SLANG_IMAGE_FORMAT_unknown", 0, "derived"), ("SLANG_IMAGE_FORMAT_rgba32f", 1, "derived"),
        ("SLANG_IMAGE_FORMAT_bgra8", 42, "derived")]),
    # Implicit *semantic* values, the topmost of which is also max + 1. Only the name says no.
    enum("SlangScope", [
        ("SLANG_SCOPE_NONE", 0, "derived"), ("SLANG_SCOPE_THREAD", 1, "derived"),
        ("SLANG_SCOPE_WAVE", 2, "derived"), ("SLANG_SCOPE_THREAD_GROUP", 3, "derived")]),
]}


class _Kind:
    def __init__(self, expression):
        self._expression = expression

    def is_expression(self):
        return self._expression


class _Child:
    def __init__(self, kind):
        self.kind = kind


class _Cursor:
    def __init__(self, *children):
        self._children = children

    def get_children(self):
        return iter(self._children)


EXPR = _Kind(True)    # an initializer: IntegerLiteral, DeclRefExpr, UnaryOperator, ...
ATTR = _Kind(False)   # UNEXPOSED_ATTR, e.g. slang.h's SLANG_DEPRECATED -> [[deprecated]]


class DerivedEnumerator(unittest.TestCase):
    def test_no_children_is_derived(self):
        self.assertTrue(extract_api.is_derived_enumerator(_Cursor()))

    def test_expression_child_is_explicit(self):
        self.assertFalse(extract_api.is_derived_enumerator(_Cursor(_Child(EXPR))))

    def test_attribute_alone_does_not_imply_an_initializer(self):
        """`SLANG_DEPRECATED Foo,` has an attribute child but no value of its own."""
        self.assertTrue(extract_api.is_derived_enumerator(_Cursor(_Child(ATTR))))

    def test_attribute_plus_initializer_is_explicit(self):
        self.assertFalse(
            extract_api.is_derived_enumerator(_Cursor(_Child(ATTR), _Child(EXPR))))


class CountSentinels(unittest.TestCase):
    def setUp(self):
        self.sentinels = extract_api.count_sentinels(MODEL)

    def test_recognises_exactly_the_real_sentinels(self):
        self.assertEqual(set(self.sentinels), {
            ("CompilerOptionName", "CountOf"),
            ("SlangStage", "SLANG_STAGE_COUNT"),
            ("SlangParameterCategory", "SLANG_PARAMETER_CATEGORY_COUNT"),
        })
        self.assertEqual(self.sentinels[("CompilerOptionName", "CountOf")], 158)

    def test_frozen_historical_markers_stay_locked(self):
        # Count-named, but explicitly valued: never relaxed.
        for key in (("CompilerOptionName", "CountOfParsableOptions"),
                    ("SlangParameterCategory", "SLANG_PARAMETER_CATEGORY_COUNT_V1")):
            self.assertNotIn(key, self.sentinels)

    def test_x_macro_enum_is_never_relaxed(self):
        # All 43 SlangImageFormat enumerators are derived; relaxing them would gut the enum.
        for v in ("SLANG_IMAGE_FORMAT_unknown", "SLANG_IMAGE_FORMAT_bgra8"):
            self.assertNotIn(("SlangImageFormat", v), self.sentinels)

    def test_derived_semantic_values_are_never_relaxed(self):
        # SLANG_SCOPE_THREAD_GROUP is derived AND max + 1; only the name test excludes it.
        for v in ("SLANG_SCOPE_NONE", "SLANG_SCOPE_THREAD_GROUP"):
            self.assertNotIn(("SlangScope", v), self.sentinels)

    def test_sentinel_must_sit_one_past_the_top(self):
        # A derived, count-named enumerator that is NOT max + 1 is not a terminal count.
        odd = {"enums": [enum("E", [("A", 0), ("B", 9), ("E_COUNT", 3, "derived")])]}
        self.assertEqual(extract_api.count_sentinels(odd), {})

    def test_explicit_initializer_disqualifies(self):
        pinned = {"enums": [enum("E", [("A", 0), ("E_COUNT", 1)])]}
        self.assertEqual(extract_api.count_sentinels(pinned), {})


class EnforceLock(unittest.TestCase):
    def lock(self, lines):
        fh = tempfile.NamedTemporaryFile("w", suffix=".lock", delete=False)
        fh.write("# test lock\n" + "\n".join(lines) + "\n")
        fh.close()
        return Path(fh.name)

    def enforce(self, old, new, sentinels=None):
        return extract_api.enforce_lock(self.lock(old), sorted(new), sentinels or {})

    def test_sentinel_may_grow(self):
        """The exact failure the canary reported: CountOf 156 -> 158 after two legal appends."""
        old = ["enum CompilerOptionName CountOf 156",
               "enum CompilerOptionName WarningLevel 155"]
        new = ["enum CompilerOptionName CountOf 158",
               "enum CompilerOptionName WarningLevel 155",
               "enum CompilerOptionName SeparateDebugInfoOutput 156",
               "enum CompilerOptionName DebugInfoIncludeSource 157"]
        self.assertEqual(
            self.enforce(old, new, {("CompilerOptionName", "CountOf"): 158}), "verified")

    def test_sentinel_may_not_shrink(self):
        """Going backwards means enumerators were removed -- a real break."""
        with self.assertRaises(SystemExit):
            self.enforce(["enum CompilerOptionName CountOf 158"],
                         ["enum CompilerOptionName CountOf 156"],
                         {("CompilerOptionName", "CountOf"): 156})

    def test_sentinel_may_not_vanish(self):
        """Renamed or deleted: not in the new sentinel map, so it falls back to exact match."""
        with self.assertRaises(SystemExit):
            self.enforce(["enum CompilerOptionName CountOf 156"],
                         ["enum CompilerOptionName CountOfOptions 158"], {})

    def test_renumbering_a_real_enumerator_still_fails(self):
        with self.assertRaises(SystemExit):
            self.enforce(["enum CompilerOptionName WarningLevel 155"],
                         ["enum CompilerOptionName WarningLevel 154"],
                         {("CompilerOptionName", "CountOf"): 158})

    def test_relaxation_does_not_leak_to_other_enumerators(self):
        """A sentinel entry for one enum must not excuse drift in a same-named value elsewhere."""
        with self.assertRaises(SystemExit):
            self.enforce(["enum PathKind CountOf 4", "enum CompilerOptionName CountOf 156"],
                         ["enum PathKind CountOf 3", "enum CompilerOptionName CountOf 158"],
                         {("CompilerOptionName", "CountOf"): 158,
                          ("PathKind", "CountOf"): 3})

    def test_unchanged_facts_still_verify(self):
        lines = ["enum SlangScope SLANG_SCOPE_WAVE 2", "fn spReflection_GetTypeName",
                 "iface ISession 5 loadModule", "struct SessionDesc field targets 16"]
        self.assertEqual(self.enforce(lines, lines), "verified")

    def test_removed_function_still_fails(self):
        with self.assertRaises(SystemExit):
            self.enforce(["fn spCreateSession"], ["fn spDestroySession"])

    def test_reordered_vtable_still_fails(self):
        with self.assertRaises(SystemExit):
            self.enforce(["iface ISession 5 loadModule"], ["iface ISession 6 loadModule"])

    def test_struct_may_grow_but_not_shrink(self):
        self.assertEqual(self.enforce(["struct SessionDesc size 64"],
                                      ["struct SessionDesc size 72"]), "verified")
        with self.assertRaises(SystemExit):
            self.enforce(["struct SessionDesc size 64"], ["struct SessionDesc size 56"])

    def test_moved_struct_field_still_fails(self):
        with self.assertRaises(SystemExit):
            self.enforce(["struct SessionDesc field targets 16"],
                         ["struct SessionDesc field targets 24"])


class CommittedLock(unittest.TestCase):
    """Against the real api/slang-abi.lock, so the tests bind to shipped data, not a mock."""

    LOCK = Path(__file__).resolve().parents[2] / "api" / "slang-abi.lock"

    def setUp(self):
        if not self.LOCK.exists():
            self.skipTest(f"{self.LOCK} not present")
        self.lines = [l for l in self.LOCK.read_text().splitlines()
                      if l and not l.startswith("#")]

    def test_committed_lock_verifies_against_itself(self):
        self.assertEqual(extract_api.enforce_lock(self.LOCK, sorted(self.lines)), "verified")

    def test_upstream_append_no_longer_trips_the_canary(self):
        """Replay the drift that failed run 32002910570 against the committed lock."""
        self.assertIn("enum CompilerOptionName CountOf 156", self.lines)
        new = [l for l in self.lines if l != "enum CompilerOptionName CountOf 156"] + [
            "enum CompilerOptionName CountOf 158",
            "enum CompilerOptionName SeparateDebugInfoOutput 156",
            "enum CompilerOptionName DebugInfoIncludeSource 157"]
        self.assertEqual(
            extract_api.enforce_lock(self.LOCK, sorted(new),
                                     {("CompilerOptionName", "CountOf"): 158}),
            "verified")

    def test_x_macro_enumerator_in_the_committed_lock_stays_exact(self):
        victim = next(l for l in self.lines if l.startswith("enum SlangImageFormat "))
        name, value = victim.split()[2], int(victim.split()[3])
        new = [l for l in self.lines if l != victim] + [
            f"enum SlangImageFormat {name} {value + 1}"]
        with self.assertRaises(SystemExit):
            extract_api.enforce_lock(self.LOCK, sorted(new),
                                     {("CompilerOptionName", "CountOf"): 158})


if __name__ == "__main__":
    unittest.main(verbosity=2)

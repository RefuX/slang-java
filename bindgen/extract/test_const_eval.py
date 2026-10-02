#!/usr/bin/env python3
"""Unit tests for global-constant extraction (extract_api.const_var_value).

Needs libclang, unlike test_abi_lock.py: what is under test is clang's own constant folding,
so stubbing it out would test nothing. Parses an in-memory snippet, so no Slang checkout or SDK.

    bindgen/extract/.venv/bin/python bindgen/extract/test_const_eval.py

Background: Slang 2026.16.1 added `kUnboundedSyntheticResourceArraySize = ~uint32_t(0)`. The
extractor took the first integer literal in an initializer's AST, so it read that as 0, and the
lock would have frozen the wrong value. Every case below that is not a bare literal or a bare
enum reference returned a wrong value under that scheme.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extract_api  # noqa: E402

ci = extract_api.ci

SOURCE = """
typedef int int32_t;
typedef unsigned int uint32_t;
typedef unsigned long long uint64_t;
enum Flags : uint32_t { kFlagA = 1u << 4, kFlagB = 1u << 10 };
inline constexpr uint32_t kAllBits = ~uint32_t(0);
inline constexpr uint32_t kHexLiteral = 0xffffffffu;
inline constexpr uint32_t kEnumRef = kFlagB;
inline constexpr uint32_t kOred = kFlagA | kFlagB;
inline constexpr uint32_t kShifted = 1u << 31;
inline constexpr int32_t kNegative = -1;
inline constexpr uint64_t kWideAllBits = ~uint64_t(0);
inline constexpr float kNotAnInteger = 1.5f;
"""


class ConstVarValueTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        extract_api.configure_libclang()
        tu = ci.Index.create().parse(
            "consts.cpp", args=["-x", "c++", "-std=c++17", "-nostdinc"],
            unsaved_files=[("consts.cpp", SOURCE)])
        errors = [d.spelling for d in tu.diagnostics if d.severity >= ci.Diagnostic.Error]
        if errors:
            raise AssertionError(f"snippet did not parse: {errors}")
        cls.values = {c.spelling: extract_api.const_var_value(c)
                      for c in tu.cursor.get_children() if c.kind == ci.CursorKind.VAR_DECL}

    def test_bitwise_not_folds_to_all_bits_set(self):
        # The regression: the literal inside the cast is 0; the constant is not.
        self.assertEqual(self.values["kAllBits"], 0xFFFFFFFF)

    def test_bitwise_not_folds_at_64_bits(self):
        self.assertEqual(self.values["kWideAllBits"], 0xFFFFFFFFFFFFFFFF)

    def test_plain_literal(self):
        self.assertEqual(self.values["kHexLiteral"], 0xFFFFFFFF)

    def test_enum_reference(self):
        self.assertEqual(self.values["kEnumRef"], 1 << 10)

    def test_operators_on_enum_references(self):
        self.assertEqual(self.values["kOred"], (1 << 4) | (1 << 10))

    def test_shift(self):
        self.assertEqual(self.values["kShifted"], 1 << 31)

    def test_negative_signed(self):
        self.assertEqual(self.values["kNegative"], -1)

    def test_non_integer_is_skipped(self):
        self.assertIsNone(self.values["kNotAnInteger"])


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""slang-bindgen Stage A: extract Slang's public ABI from slang.h into a committed JSON model.

Parses the real slang.h (C++ mode) with libclang and emits:
  - api/slang-api.json  -- enums, structs (with verified offsets), COM interfaces (with vtable
                           slots and UUIDs), extern-C functions, and the reflection-wrapper
                           method -> spReflection* mapping (DESIGN.md section 9)
  - api/slang-abi.lock  -- flattened vtable order / enum values / struct offsets; regeneration
                           fails on any non-append change (mirrors Slang's own append-only
                           public-ABI policy on our side)

The model is extracted once from a primary triple and then re-parsed for all six supported
target triples; struct layouts, vtable orders, and enum values must be identical everywhere
(the only platform-width scalar in the API is `long`, which appears in no struct).

Hard validations (each is a cross-compiler ABI hazard; see DESIGN.md section 3.4):
overloaded virtuals, virtual destructors, aggregate-by-value returns or params on virtual
methods, multiple/virtual inheritance, and missing SLANG_COM_INTERFACE UUIDs.

Usage (from the repo root; see CLAUDE.md for the one-line regen command):
    bindgen/extract/.venv/bin/python bindgen/extract/extract_api.py \
        --slang-include ../slang/include --slang-version 2026.13 \
        --out api/slang-api.json --lock api/slang-abi.lock
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import clang.cindex as ci

TRIPLES = [
    "arm64-apple-macosx",
    "x86_64-apple-macosx",
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "x86_64-pc-windows-msvc",
    "aarch64-pc-windows-msvc",
]
IN_SCOPE_FILES = {"slang.h", "slang-deprecated.h", "slang-image-format-defs.h"}
# The pre-COM compile-request API is deprecated and not bound; the reflection C functions in the
# same header are the live ABI (DESIGN.md section 3.3).
REFLECTION_FN = re.compile(r"^spReflection")
EXPORT_FN = re.compile(r"^(sp[A-Z]|slang_)")
IFACE_NAME = re.compile(r"^I[A-Z]")

TK = ci.TypeKind
CK = ci.CursorKind


def die(msg):
    sys.exit(f"extract_api: FATAL: {msg}")


def configure_libclang():
    """Prefer the host toolchain's libclang so the parser binary matches the resource headers
    passed via -isystem (a version mismatch breaks freestanding stdint.h, whose __INTn_C macros
    are predefined only by the matching clang). $SLANG_LIBCLANG overrides; otherwise probe the
    usual macOS and Linux locations, then fall back to the wheel-bundled library."""
    override = os.environ.get("SLANG_LIBCLANG")
    if override:
        ci.Config.set_library_file(override)
        return override
    candidates = [
        "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/libclang.dylib",
        "/Library/Developer/CommandLineTools/usr/lib/libclang.dylib",
    ]
    candidates += sorted(glob.glob("/usr/lib/llvm-*/lib/libclang.so*"), reverse=True)
    candidates += sorted(glob.glob("/usr/lib/*/libclang-*.so*"), reverse=True)
    candidates += ["/usr/lib/libclang.so", "/usr/lib/x86_64-linux-gnu/libclang.so"]
    for cand in candidates:
        if Path(cand).exists():
            ci.Config.set_library_file(cand)
            return cand
    return "bundled"


def resource_dir():
    # $SLANG_CLANG lets CI point at a versioned binary (e.g. clang-18) that matches the libclang
    # the parser loads — a version mismatch breaks the freestanding stdint.h headers.
    clang = os.environ.get("SLANG_CLANG", "clang")
    return subprocess.check_output([clang, "-print-resource-dir"], text=True).strip()


def sdk_path():
    return subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()


def clang_args(include_dir, triple, res_dir, sdk):
    args = ["-x", "c++", "-std=c++17", "-target", triple, "-I", str(include_dir)]
    if "apple" in triple:
        args += ["-isysroot", sdk]
    else:
        # Non-host triples parse freestanding: slang.h only needs <stddef.h>/<inttypes.h>
        # (clang's own resource headers) plus an ABI-irrelevant <features.h> probe (shimmed).
        shims = Path(__file__).resolve().parent / "shims"
        # Shims first so <inttypes.h>/<features.h> resolve there; everything else falls through
        # to clang's freestanding resource headers.
        args += ["-ffreestanding", "-nostdinc",
                 "-isystem", str(shims), "-isystem", str(Path(res_dir) / "include")]
    return args


def parse_tu(index, include_dir, triple, res_dir, sdk):
    tu = index.parse(
        str(include_dir / "slang.h"), args=clang_args(include_dir, triple, res_dir, sdk))
    errors = [f"{d.location}: {d.spelling}" for d in tu.diagnostics
              if d.severity >= ci.Diagnostic.Error]
    if errors:
        die(f"{triple}: slang.h did not parse:\n  " + "\n  ".join(errors[:10]))
    return tu


def in_scope(cursor):
    f = cursor.location.file
    return f is not None and Path(f.name).name in IN_SCOPE_FILES


def in_deprecated_header(cursor):
    """slang-deprecated.h contributions are excluded except the spReflection* functions — the
    compile-request era API (ICompileRequest and the other sp* functions) is not bound."""
    return Path(cursor.location.file.name).name == "slang-deprecated.h"


def doc_of(cursor):
    raw = cursor.raw_comment
    if not raw:
        return None
    # Strip comment framing; keep the text as-is otherwise.
    lines = []
    for line in raw.splitlines():
        line = line.strip()
        for prefix in ("/**", "/*!", "/*", "///", "//", "*/"):
            if line.startswith(prefix):
                line = line[len(prefix):]
        line = line.removesuffix("*/").strip()
        if line.startswith("*"):
            line = line[1:].strip()
        lines.append(line)
    text = "\n".join(lines).strip()
    return text or None


def typedef_chain_names(t):
    names, guard = [], 0
    while guard < 16:
        guard += 1
        names.append(t.spelling)
        decl = t.get_declaration()
        if decl is not None and decl.kind == CK.TYPEDEF_DECL:
            t = decl.underlying_typedef_type
        else:
            break
    return names


def vocab(t):
    """Maps a clang type to the small fixed vocabulary the Java codegen understands."""
    chain = typedef_chain_names(t)
    can = t.get_canonical()
    k = can.kind
    if k == TK.VOID:
        return {"k": "void"}
    if k == TK.BOOL:
        return {"k": "bool"}
    if k in (TK.POINTER, TK.LVALUEREFERENCE, TK.RVALUEREFERENCE):
        return {"k": "ptr", "to": can.get_pointee().spelling}
    if k == TK.ENUM:
        decl = can.get_declaration()
        return {"k": "enum", "name": decl.spelling, "repr": vocab(decl.enum_type)}
    if k == TK.RECORD:
        return {"k": "struct", "name": can.get_declaration().spelling}
    if k == TK.CONSTANTARRAY:
        return {"k": "array", "elem": vocab(can.element_type), "n": can.element_count}
    if k in (TK.FLOAT,):
        return {"k": "f32"}
    if k in (TK.DOUBLE,):
        return {"k": "f64"}
    # size_t and friends: platform-independent 8 bytes on every supported ABI, but keep them
    # symbolic so the codegen can use the canonical layout.
    if any("size_t" in n for n in chain):
        return {"k": "usize"}
    if k in (TK.LONG, TK.ULONG):
        # On the primary (LP64 Darwin) triple, int64_t canonicalizes to `long long`, so a bare
        # LONG here is a genuine platform-width C long -- the one type whose width differs
        # across our targets (4 bytes on LLP64 Windows, 8 elsewhere).
        return {"k": "long" if k == TK.LONG else "ulong"}
    signed_map = {TK.SCHAR: "i8", TK.CHAR_S: "i8", TK.SHORT: "i16", TK.INT: "i32",
                  TK.LONGLONG: "i64"}
    unsigned_map = {TK.UCHAR: "u8", TK.CHAR_U: "u8", TK.USHORT: "u16", TK.UINT: "u32",
                    TK.ULONGLONG: "u64"}
    if k in signed_map:
        return {"k": signed_map[k]}
    if k in unsigned_map:
        return {"k": unsigned_map[k]}
    die(f"unmapped type: {t.spelling} (canonical {can.spelling}, kind {k})")


def expand_fields(record_type, base_offset=0):
    """Field list with byte offsets, inlining anonymous structs and (anonymous or named) unions
    as overlapping members at the union's offset — e.g. SpecializationArg's {type*|expr*} union
    becomes two fields sharing offset 8. Offsets are clang-computed, never accumulated by hand,
    so overlap needs no special casing downstream (generated accessors are offset-based)."""
    out = []
    for f in record_type.get_fields():
        offset = base_offset + f.get_field_offsetof() // 8
        can = f.type.get_canonical()
        if can.kind == TK.RECORD:
            decl = can.get_declaration()
            if decl.kind == CK.UNION_DECL or not f.spelling:
                out.extend(expand_fields(can, offset))
                continue
        entry = {"name": f.spelling, "offset": offset, "type": vocab(f.type)}
        if doc_of(f):
            entry["doc"] = doc_of(f)
        out.append(entry)
    return out


def extract_uuid(class_cursor):
    for child in class_cursor.get_children():
        if child.kind == CK.CXX_METHOD and child.spelling == "getTypeGuid":
            # Macro-expanded cursors report tokens from the invocation-site extent, which can
            # bleed into surrounding text (e.g. `extern "C"`); keep integer literals only and
            # take the trailing 11 -- the a,b,c,d0..d7 of SLANG_COM_INTERFACE in order.
            literals = [tok.spelling for tok in child.get_tokens()
                        if tok.kind == ci.TokenKind.LITERAL]
            values = [int(x.rstrip("uUlL"), 0) for x in literals
                      if re.fullmatch(r"0[xX][0-9a-fA-F]+|\d+", x.rstrip("uUlL"))]
            if len(values) < 11:
                die(f"{class_cursor.spelling}: getTypeGuid has {len(values)} int literals, want 11")
            a, b, c, *d = values[-11:]
            return f"{a:08x}-{b:04x}-{c:04x}-{d[0]:02x}{d[1]:02x}-" + \
                   "".join(f"{x:02x}" for x in d[2:])
    return None


def walk_named_decls(tu):
    """Yields in-scope declaration cursors, recursing only through namespaces/linkage specs."""
    def rec(cursor):
        for child in cursor.get_children():
            if not in_scope(child):
                continue
            if child.kind in (CK.NAMESPACE, CK.LINKAGE_SPEC, CK.UNEXPOSED_DECL):
                yield from rec(child)
            else:
                yield child
    yield from rec(tu.cursor)


def build_model(tu, slang_version, primary_triple):
    enums, structs, interfaces, functions, wrappers = {}, {}, {}, {}, []
    misc_constants = []

    for cursor in walk_named_decls(tu):
        if cursor.kind == CK.ENUM_DECL and cursor.is_definition():
            if cursor.semantic_parent.kind not in (CK.NAMESPACE, CK.TRANSLATION_UNIT,
                                                   CK.LINKAGE_SPEC, CK.UNEXPOSED_DECL):
                continue  # nested enums belong to the C++ wrapper classes (M4 concern)
            values = [{"name": v.spelling, "value": v.enum_value,
                       **({"doc": doc_of(v)} if doc_of(v) else {})}
                      for v in cursor.get_children() if v.kind == CK.ENUM_CONSTANT_DECL]
            name = cursor.spelling
            if not name or "(" in name:
                # Anonymous enums (libclang spells them "(unnamed enum at ...)") carry real
                # constants such as kDefaultTargetFlags and kSessionFlags_None; pool them.
                misc_constants.extend(values)
                continue
            enums[name] = {
                "name": name, "repr": vocab(cursor.enum_type),
                **({"doc": doc_of(cursor)} if doc_of(cursor) else {}), "values": values}

        elif cursor.kind in (CK.STRUCT_DECL, CK.CLASS_DECL) and cursor.is_definition():
            name = cursor.spelling
            if not name:
                continue
            bases = [b for b in cursor.get_children() if b.kind == CK.CXX_BASE_SPECIFIER]
            methods = [m for m in cursor.get_children() if m.kind == CK.CXX_METHOD]
            virtuals = [m for m in methods if m.is_virtual_method()]

            if IFACE_NAME.match(name) and (bases or name == "ISlangUnknown"):
                if not in_deprecated_header(cursor):
                    interfaces[name] = extract_interface(cursor, name, bases, virtuals)
                continue

            if virtuals or bases:
                # C++-only helper classes (ComPtr and friends): not ABI structs.
                wrappers.extend(extract_wrapper_calls(cursor, name, methods))
                continue

            fields = expand_fields(cursor.type)
            if not fields:
                # Field-less classes with inline methods are the reflection wrapper classes
                # (TypeReflection, ProgramLayout, ...): opaque handles whose methods forward to
                # spReflection* C functions. Mine that mapping for M4.
                if methods:
                    wrappers.extend(extract_wrapper_calls(cursor, name, methods))
                continue
            structs[name] = {
                "name": name,
                "size": cursor.type.get_size(),
                "align": cursor.type.get_align(),
                **({"doc": doc_of(cursor)} if doc_of(cursor) else {}),
                "fields": fields,
            }

        elif cursor.kind == CK.VAR_DECL and in_scope(cursor):
            # Global constexpr integer constants (e.g. `inline constexpr SlangTargetFlags
            # kDefaultTargetFlags = SLANG_TARGET_FLAG_GENERATE_SPIRV_DIRECTLY;`) are API
            # constants just like anonymous-enum members; pool them alongside.
            value = const_var_value(cursor)
            if value is not None:
                entry = {"name": cursor.spelling, "value": value}
                if doc_of(cursor):
                    entry["doc"] = doc_of(cursor)
                misc_constants.append(entry)

        elif cursor.kind == CK.FUNCTION_DECL and EXPORT_FN.match(cursor.spelling):
            header = Path(cursor.location.file.name).name
            if header == "slang-deprecated.h" and not REFLECTION_FN.match(cursor.spelling):
                continue  # the deprecated compile-request API is not bound
            functions[cursor.spelling] = {
                "name": cursor.spelling,
                "group": "reflection" if REFLECTION_FN.match(cursor.spelling) else "core",
                "ret": vocab(cursor.result_type),
                "params": [{"name": p.spelling or f"arg{i}", "type": vocab(p.type)}
                           for i, p in enumerate(cursor.get_arguments())],
                **({"doc": doc_of(cursor)} if doc_of(cursor) else {}),
            }

    if misc_constants:
        enums["SlangMiscConstants"] = {
            "name": "SlangMiscConstants", "repr": {"k": "i32"},
            "doc": "Constants pooled from slang.h's anonymous enums (session flags, default "
                   "target flags, ...). The names are ordinary global C identifiers, so they "
                   "are collision-free.",
            "values": misc_constants}

    resolve_vtables(interfaces)
    return {
        "slangVersion": slang_version,
        "generatedBy": "slang-bindgen extract_api.py",
        "primaryTriple": primary_triple,
        "validatedTriples": TRIPLES,
        "enums": [enums[k] for k in sorted(enums)],
        "structs": [structs[k] for k in sorted(structs)],
        "interfaces": [interfaces[k] for k in sorted(interfaces)],
        "functions": [functions[k] for k in sorted(functions)],
        "reflectionWrappers": sorted(wrappers, key=lambda w: (w["class"], w["method"])),
    }


def extract_interface(cursor, name, bases, virtuals):
    if len(bases) > 1:
        die(f"{name}: multiple inheritance is not a COM shape")
    for child in cursor.get_children():
        if child.kind == CK.DESTRUCTOR and child.is_virtual_method():
            die(f"{name}: virtual destructor would skew vtable slots across ABIs")
    seen = {}
    methods = []
    for m in virtuals:
        if m.spelling in seen:
            die(f"{name}::{m.spelling}: overloaded virtuals order differently on MSVC")
        seen[m.spelling] = True
        ret = vocab(m.result_type)
        if ret["k"] == "struct":
            die(f"{name}::{m.spelling}: aggregate return from a virtual differs across ABIs")
        params = []
        for i, p in enumerate(m.get_arguments()):
            pv = vocab(p.type)
            if pv["k"] == "struct":
                die(f"{name}::{m.spelling}: by-value aggregate param on a virtual")
            params.append({"name": p.spelling or f"arg{i}", "type": pv})
        methods.append({"name": m.spelling, "ret": ret, "params": params,
                        **({"doc": doc_of(m)} if doc_of(m) else {})})
    base = bases[0].type.spelling.split("::")[-1] if bases else None
    uuid = extract_uuid(cursor)
    if uuid is None and name != "ISlangUnknown":
        die(f"{name}: no SLANG_COM_INTERFACE uuid found")
    return {"name": name, "base": base, "uuid": uuid,
            "experimental": "_Experimental" in name,
            **({"doc": doc_of(cursor)} if doc_of(cursor) else {}),
            "methods": methods}


def resolve_vtables(interfaces):
    # ISlangUnknown is declared through a macro; libclang still sees its three virtuals, but be
    # explicit if it ever ends up empty.
    def base_slots(name):
        info = interfaces.get(name)
        if info is None:
            die(f"interface base {name} not extracted")
        b = info["base"]
        return (base_slots(b) if b else 0) + len(info["methods"])

    for info in interfaces.values():
        start = base_slots(info["base"]) if info["base"] else 0
        for i, m in enumerate(info["methods"]):
            m["slot"] = start + i
        info["vtableSize"] = start + len(info["methods"])


def const_var_value(cursor):
    """Value of a global integer constant VarDecl: either a literal initializer or a reference
    to an enum constant. Returns None for anything else (non-integers, complex expressions)."""
    try:
        if vocab(cursor.type)["k"] not in (
                "i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "enum"):
            return None
    except SystemExit:
        return None
    for node in cursor.walk_preorder():
        if node.kind == CK.DECL_REF_EXPR and node.referenced is not None \
                and node.referenced.kind == CK.ENUM_CONSTANT_DECL:
            return node.referenced.enum_value
        if node.kind == CK.INTEGER_LITERAL:
            tokens = [t.spelling for t in node.get_tokens()]
            if tokens:
                return int(tokens[0].rstrip("uUlL"), 0)
    return None


def extract_wrapper_calls(cursor, name, methods):
    """Best-effort mapping of C++ reflection-wrapper methods to their sp* callee."""
    out = []
    for m in methods:
        if m.is_static_method() or m.is_virtual_method():
            continue
        callee = None
        for node in m.walk_preorder():
            if node.kind == CK.CALL_EXPR and node.spelling and \
                    node.spelling.startswith("sp"):
                callee = node.spelling
                break
        if callee:
            out.append({"class": name, "method": m.spelling, "callee": callee})
    return out


def layout_snapshot(tu):
    """The per-triple facts that must be identical everywhere: struct layouts, vtable order,
    enum values."""
    snap = {"structs": {}, "vtables": {}, "enums": {}}
    ifaces = {}
    for cursor in walk_named_decls(tu):
        if cursor.kind in (CK.STRUCT_DECL, CK.CLASS_DECL) and cursor.is_definition():
            name = cursor.spelling
            bases = [b for b in cursor.get_children() if b.kind == CK.CXX_BASE_SPECIFIER]
            if IFACE_NAME.match(name or "") and (bases or name == "ISlangUnknown"):
                if not in_deprecated_header(cursor):
                    ifaces[name] = (
                        bases[0].type.spelling.split("::")[-1] if bases else None,
                        [m.spelling for m in cursor.get_children()
                         if m.kind == CK.CXX_METHOD and m.is_virtual_method()])
                continue
            if not name or bases or any(
                    m.kind == CK.CXX_METHOD and m.is_virtual_method()
                    for m in cursor.get_children()):
                continue
            fields = expand_fields(cursor.type)
            if fields:
                snap["structs"][name] = {
                    "size": cursor.type.get_size(),
                    "fields": {f["name"]: f["offset"] for f in fields}}
        elif cursor.kind == CK.ENUM_DECL and cursor.is_definition() and cursor.spelling:
            snap["enums"][cursor.spelling] = {
                v.spelling: v.enum_value for v in cursor.get_children()
                if v.kind == CK.ENUM_CONSTANT_DECL}

    def flat(name):
        base, methods = ifaces[name]
        return (flat(base) if base else []) + methods

    snap["vtables"] = {name: flat(name) for name in ifaces}
    return snap


def validate_across_triples(index, include_dir, res_dir, sdk, primary_snap):
    for triple in TRIPLES[1:]:
        tu = parse_tu(index, include_dir, triple, res_dir, sdk)
        snap = layout_snapshot(tu)
        for section in ("structs", "vtables", "enums"):
            if snap[section] != primary_snap[section]:
                for key in sorted(set(primary_snap[section]) | set(snap[section])):
                    if primary_snap[section].get(key) != snap[section].get(key):
                        print(f"  DIFF {section}.{key}:\n    primary={primary_snap[section].get(key)}\n    {triple}={snap[section].get(key)}", file=sys.stderr)
                die(f"{triple}: {section} layout differs from primary triple")
        print(f"  validated {triple}")


def lock_entries(model):
    lines = []
    for e in model["enums"]:
        for v in e["values"]:
            lines.append(f"enum {e['name']} {v['name']} {v['value']}")
    for s in model["structs"]:
        lines.append(f"struct {s['name']} size {s['size']}")
        for f in s["fields"]:
            lines.append(f"struct {s['name']} field {f['name']} {f['offset']}")
    for i in model["interfaces"]:
        for m in i["methods"]:
            lines.append(f"iface {i['name']} {m['slot']} {m['name']}")
    for f in model["functions"]:
        lines.append(f"fn {f['name']}")
    return sorted(lines)


def enforce_lock(lock_path, new_lines):
    """Append-only ABI enforcement: every previously locked fact must still hold. Struct sizes
    may grow (structureSize-versioned appends); everything else must be byte-identical."""
    if not lock_path.exists():
        return "created"
    old = [l for l in lock_path.read_text().splitlines()
           if l and not l.startswith("#")]
    new_set = set(new_lines)
    new_sizes = {tuple(l.split()[:2]): int(l.split()[3]) for l in new_lines
                 if l.startswith("struct") and l.split()[2] == "size"}
    problems = []
    for line in old:
        parts = line.split()
        if parts[0] == "struct" and parts[2] == "size":
            new_size = new_sizes.get((parts[0], parts[1]))
            if new_size is None or new_size < int(parts[3]):
                problems.append(f"struct shrank or vanished: {line}")
        elif line not in new_set:
            problems.append(f"locked ABI fact changed or disappeared: {line}")
    if problems:
        die("ABI lock violations (non-append change in slang.h?):\n  " +
            "\n  ".join(problems[:20]))
    return "verified"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--slang-include", required=True, type=Path)
    ap.add_argument("--slang-version", required=True)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--lock", required=True, type=Path)
    ap.add_argument("--reset-lock", action="store_true",
                    help="Skip append-only enforcement and rewrite the lock from scratch. Only "
                         "for lock-format migrations or a deliberate, justified ABI rebase — "
                         "say why in the commit message.")
    ap.add_argument("--verify", action="store_true",
                    help="Enforce the lock and write the model to --out for diffing, but do NOT "
                         "rewrite the committed lock. Used by the ABI-drift canary against a "
                         "newer Slang's headers.")
    ap.add_argument("--primary-triple", default=TRIPLES[0],
                    help="Triple to extract the model from (default: the macOS host triple). Set "
                         "a Linux triple when running where no macOS SDK is available.")
    ap.add_argument("--skip-cross-triple", action="store_true",
                    help="Skip the six-triple layout cross-check (needs every target's headers/"
                         "SDK). The lock still catches vtable/enum/offset drift on the primary "
                         "triple.")
    args = ap.parse_args()

    lib = configure_libclang()
    primary = args.primary_triple
    triples = [primary] if args.skip_cross_triple else \
        [primary] + [t for t in TRIPLES if t != primary]
    res = resource_dir()
    # xcrun only exists on macOS; the SDK is only needed for apple triples.
    sdk = sdk_path() if any("apple" in t for t in triples) else ""
    print(f"libclang: {lib}")

    index = ci.Index.create()
    print(f"parsing {args.slang_include}/slang.h ({primary})")
    tu = parse_tu(index, args.slang_include, primary, res, sdk)
    model = build_model(tu, args.slang_version, primary)

    if args.skip_cross_triple:
        print("skipping cross-triple layout validation (single-triple run)")
    else:
        print("validating layout across target triples")
        validate_across_triples(index, args.slang_include, res, sdk, layout_snapshot(tu))

    lines = lock_entries(model)
    if args.reset_lock:
        print("WARNING: --reset-lock given; append-only ABI enforcement skipped for this run")
        status = "reset"
    else:
        status = enforce_lock(args.lock, lines)  # raises on non-append (breaking) drift
    # --verify (the canary) enforces the lock but never rewrites the committed one.
    if not args.verify:
        args.lock.parent.mkdir(parents=True, exist_ok=True)
        args.lock.write_text(
            "# slang-java ABI lock -- append-only; regenerated by extract_api.py\n"
            f"# slang {args.slang_version}\n" + "\n".join(lines) + "\n")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(model, indent=1, sort_keys=True, ensure_ascii=True) + "\n")

    print(f"model: {len(model['interfaces'])} interfaces "
          f"({sum(len(i['methods']) for i in model['interfaces'])} methods), "
          f"{len(model['enums'])} enums, {len(model['structs'])} structs, "
          f"{len(model['functions'])} functions "
          f"({sum(1 for f in model['functions'] if f['group'] == 'reflection')} reflection), "
          f"{len(model['reflectionWrappers'])} wrapper mappings")
    print(f"wrote {args.out} ({args.out.stat().st_size // 1024} KiB); lock {status}")


if __name__ == "__main__":
    main()

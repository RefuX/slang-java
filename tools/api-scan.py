#!/usr/bin/env python3
"""Prototype scanner of Slang's public API surface — the seed of slang-bindgen Stage A.

Usage:
    python3 tools/api-scan.py [path-to-slang-include-dir]   (default: ../slang/include)

Regex-based structural scan of include/slang.h that reports:
  - COM interfaces: name, base, UUID presence, own-method count, full vtable size
    (base-first, declaration order — the COM slot order on both MSVC and Itanium
    ABIs *provided* the validations below hold)
  - ABI-hazard flags, each of which would break single-slot-table dispatch:
      * overloaded virtual methods within one interface (MSVC reverses adjacent
        overload order relative to Itanium)
      * virtual destructors (1 slot on MSVC, 2 on Itanium)
      * non-pointer, non-integral virtual return types (aggregate returns from
        member functions use a hidden pointer on MSVC even when register-sized;
        scalar oddities like `long` are platform-width and need canonical layouts)
  - extern-C export inventory for slang.h and slang-deprecated.h (the latter holds
    the live spReflection* C ABI that slang.h's inline C++ wrappers call)

This is a survey tool, NOT a code generator: regex parsing is good enough to
inventory a clang-formatted header, but slot-perfect codegen must come from the
libclang-based extractor (see DESIGN.md §9). Known limitations: no preprocessor
evaluation, no parameter extraction, assumes the repo's clang-format layout.
"""
import collections
import re
import sys
from pathlib import Path

include_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else \
    Path(__file__).resolve().parent.parent.parent / "slang" / "include"
SLANG = include_dir / "slang.h"
DEPR = include_dir / "slang-deprecated.h"
if not SLANG.exists():
    sys.exit(f"slang.h not found under {include_dir} — pass the include dir as argv[1]")

src = SLANG.read_text()

# ---- interfaces ------------------------------------------------------------
# Match `struct IFoo : public IBar` ... `};` with brace at the same indentation
# as the struct keyword (slang.h nests some interfaces one level deep).
iface_re = re.compile(
    r'^([ \t]*)(?:struct|class)\s+(I[A-Za-z0-9_]+)\s*:\s*public\s+([A-Za-z0-9:]+)'
    r'[ \t]*\r?\n\1\{(.*?)\r?\n\1\};',
    re.S | re.M)
uuid_re = re.compile(r'SLANG_COM_INTERFACE\(')
meth_re = re.compile(
    r'virtual\s+(?:SLANG_NO_THROW\s+)?'
    r'([A-Za-z_][A-Za-z0-9_:<>\s\*&]*?)\s+SLANG_MCALL\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(')
dtor_re = re.compile(r'virtual\s+~')

SAFE_RETURNS = {
    'void', 'bool', 'SlangResult', 'size_t', 'int', 'unsigned int',
    'SlangInt', 'SlangUInt', 'SlangInt32', 'SlangUInt32',
    'int32_t', 'uint32_t', 'int64_t', 'uint64_t',
    'SlangCapabilityID', 'SlangSourceLanguage', 'SlangPassThrough',
    'SlangCompileTarget', 'SlangProfileID',
}

interfaces = {}
for m in iface_re.finditer(src):
    name, base, body = m.group(2), m.group(3), m.group(4)
    interfaces[name] = dict(
        base=base.split('::')[-1],
        has_uuid=bool(uuid_re.search(body)),
        has_virtual_dtor=bool(dtor_re.search(body)),
        methods=[(' '.join(mm.group(1).split()), mm.group(2))
                 for mm in meth_re.finditer(body)])

# ISlangUnknown is declared via macro (SLANG_DECLARE_ISLANGUNKNOWN); register manually.
interfaces.setdefault('ISlangUnknown', dict(
    base=None, has_uuid=True, has_virtual_dtor=False,
    methods=[('SlangResult', 'queryInterface'),
             ('uint32_t', 'addRef'),
             ('uint32_t', 'release')]))


def vtable(name):
    """Full vtable (base methods first, declaration order) for interface `name`."""
    info = interfaces.get(name)
    if info is None:
        return []
    return (vtable(info['base']) if info['base'] else []) + info['methods']


problems = []
print(f"{'interface':38}{'base':28}{'own':>4} {'vtbl':>5}  flags")
for name, info in sorted(interfaces.items()):
    own = info['methods']
    dups = [n for n, c in collections.Counter(n for _, n in own).items() if c > 1]
    weird = [(r, n) for r, n in own if '*' not in r and r not in SAFE_RETURNS]
    flags = []
    if dups:
        flags.append('OVERLOADED:' + ','.join(dups))
        problems.append((name, 'overload', dups))
    if info['has_virtual_dtor']:
        flags.append('VIRTUAL-DTOR')
        problems.append((name, 'virtual-dtor', ''))
    for r, n in weird:
        flags.append(f'RET[{r} {n}]')
        problems.append((name, 'return', f'{r} {n}'))
    if not info['has_uuid']:
        flags.append('NO-UUID')
    print(f"{name:38}{str(info['base']):28}{len(own):>4} {len(vtable(name)):>5}  "
          f"{' '.join(flags)}")

total = sum(len(i['methods']) for i in interfaces.values())
print(f"\n{len(interfaces)} interfaces, {total} virtual methods total")

# ---- optional per-interface slot dump: api-scan.py <include> IComponentType ...
for want in sys.argv[2:]:
    print(f"\n=== {want} vtable ===")
    for i, (r, n) in enumerate(vtable(want)):
        print(f"  [{i:2}] {r} {n}")

# ---- extern-C exports ------------------------------------------------------
export_re = re.compile(
    r'SLANG_API\s+([A-Za-z_][\w:\s\*]*?)\s+(sp[A-Za-z0-9_]+|slang_[A-Za-z0-9_]+)\s*\(')
for path, label in [(SLANG, 'slang.h'), (DEPR, 'slang-deprecated.h')]:
    exports = export_re.findall(path.read_text())
    refl = [e for e in exports if e[1].startswith('spReflection')]
    print(f"\n{label}: {len(exports)} C exports ({len(refl)} spReflection*)")

print("\nABI-HAZARD SUMMARY:",
      problems if problems else "none — surface is FFM/COM-clean")

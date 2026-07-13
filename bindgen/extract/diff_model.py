#!/usr/bin/env python3
"""Report additions between two slang-api.json models (committed vs. freshly extracted).

Breaking drift (removed/changed methods, enum values, struct offsets, functions) is already
caught by the append-only lock in extract_api.py, which fails the run. This script surfaces the
*benign* additions — new interfaces/methods/enums/functions a newer Slang introduced — so the
ABI-drift canary can report "regenerate when convenient" without failing.

Usage: diff_model.py <committed.json> <fresh.json>
Exit code is always 0; additions go to stdout (and $GITHUB_STEP_SUMMARY if set).
"""
import json
import os
import sys


def symbols(model):
    out = set()
    for i in model["interfaces"]:
        for m in i["methods"]:
            out.add(f"method {i['name']}::{m['name']} (slot {m['slot']})")
    for e in model["enums"]:
        for v in e["values"]:
            out.add(f"enum {e['name']}.{v['name']} = {v['value']}")
    for f in model["functions"]:
        out.add(f"function {f['name']}")
    for s in model["structs"]:
        out.add(f"struct {s['name']} (size {s['size']})")
    return out


def main():
    committed = json.load(open(sys.argv[1]))
    fresh = json.load(open(sys.argv[2]))
    added = sorted(symbols(fresh) - symbols(committed))
    removed = sorted(symbols(committed) - symbols(fresh))

    lines = [f"Committed Slang {committed['slangVersion']} vs upstream {fresh['slangVersion']}:"]
    if not added and not removed:
        lines.append("  No API additions or removals — bindings are current.")
    if added:
        lines.append(f"  {len(added)} additions (benign — regenerate the bindings when convenient):")
        lines += [f"    + {s}" for s in added]
    if removed:
        # The append-only lock should already have failed the run before we get here; list these
        # for completeness if --verify was somehow run without the lock.
        lines.append(f"  {len(removed)} removals/changes (BREAKING — should have tripped the lock):")
        lines += [f"    - {s}" for s in removed]

    report = "\n".join(lines)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as fh:
            fh.write("## ABI drift\n\n```\n" + report + "\n```\n")


if __name__ == "__main__":
    main()

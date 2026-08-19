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


def symbols(model, sentinels=()):
    out = set()
    for i in model["interfaces"]:
        for m in i["methods"]:
            out.add(f"method {i['name']}::{m['name']} (slot {m['slot']})")
    for e in model["enums"]:
        for v in e["values"]:
            if (e["name"], v["name"]) in sentinels:
                continue  # a derived count, not an ABI value — reported separately below
            out.add(f"enum {e['name']}.{v['name']} = {v['value']}")
    for f in model["functions"]:
        out.add(f"function {f['name']}")
    for s in model["structs"]:
        out.add(f"struct {s['name']} (size {s['size']})")
    return out


def sentinel_moves(committed, fresh, sentinels):
    """Derived count sentinels shift by construction whenever options are appended, so they are
    neither an addition nor a removal — but the shift is still the clearest one-line summary of
    how much was appended, so it is worth printing."""
    def values(model):
        return {(e["name"], v["name"]): v["value"]
                for e in model["enums"] for v in e["values"]}

    was, now, moves = values(committed), values(fresh), []
    for key in sorted(sentinels):
        if key in was and key in now and was[key] != now[key]:
            moves.append(f"{key[0]}::{key[1]} {was[key]} -> {now[key]}")
    return moves


def main():
    committed = json.load(open(sys.argv[1]))
    if not os.path.exists(sys.argv[2]):
        # The extractor writes its model before enforcing the lock, so a missing file here means
        # extraction itself never finished — say that plainly rather than raising a traceback the
        # reader has to decode.
        print(f"no model at {sys.argv[2]} — extraction failed before the diff; see the log above")
        return
    fresh = json.load(open(sys.argv[2]))
    # The freshly extracted model names its own derived count sentinels, so this stays in step
    # with extract_api.py's rule instead of re-deriving it here.
    sentinels = {tuple(k) for k in fresh.get("countSentinels", [])}
    added = sorted(symbols(fresh, sentinels) - symbols(committed, sentinels))
    removed = sorted(symbols(committed, sentinels) - symbols(fresh, sentinels))
    moves = sentinel_moves(committed, fresh, sentinels)

    lines = [f"Committed Slang {committed['slangVersion']} vs upstream {fresh['slangVersion']}:"]
    if not added and not removed:
        lines.append("  No API additions or removals — bindings are current.")
    if added:
        lines.append(f"  {len(added)} additions (benign — regenerate the bindings when convenient):")
        lines += [f"    + {s}" for s in added]
    if moves:
        lines.append(f"  {len(moves)} derived count sentinel(s) moved — implied by those "
                     f"appends, not drift:")
        lines += [f"    = {m}" for m in moves]
    if removed:
        # The append-only lock fails the run before this step; these lines are the diagnosis of
        # what tripped it, which is the whole reason the model is written before enforcement.
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

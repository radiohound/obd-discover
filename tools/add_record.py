#!/usr/bin/env python3
"""Fold a contributed record into vehicles/, without ever losing what is already there.

A contributed record describes ONE run. A stopped run is real but partial -- the BMW's
aborted sweep found 16 blocks where its record holds 17 -- so replacing a record with a
newer one silently loses whatever the newer run did not reach. Every list here is UNIONED,
and the script refuses to shrink anything.

Facts that identify the vehicle (vin_pattern, model, year) must agree or the fold stops:
two different cars merging into one record is worse than two records.

    tools/add_record.py contributed.json           # fold
    tools/add_record.py contributed.json --dry-run # show what would change
"""
import json, sys, os, argparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LISTS = ("headers", "blocks", "mode21_ids")
IDENT = ("vin_pattern", "year", "make", "model", "series")


def target_for(rec):
    make, model = rec.get("make", ""), rec.get("model", "")
    if not make or not model:
        sys.exit("record needs make and model before it can be filed")
    return os.path.join(ROOT, "vehicles", make.replace("/", "-"),
                        model.replace(" ", "-").replace("/", "-") + ".json")


def fold(old, new, warn):
    out = dict(old)
    for k in IDENT:
        a, b = old.get(k), new.get(k)
        if a and b and str(a) != str(b):
            sys.exit(f"REFUSING: {k} disagrees -- existing {a!r}, incoming {b!r}. "
                     f"Two vehicles, or a mistake; either way not one record.")
        if b and not a:
            out[k] = b
    for k in LISTS:
        a, b = set(old.get(k) or []), set(new.get(k) or [])
        if a - b:
            warn(f"  incoming {k} is missing {len(a - b)} the record already has "
                 f"(partial run?) -- keeping both")
        if a | b:
            out[k] = sorted(a | b)
    # pids is {id: name}; a later scan may see more, never fewer that matter.
    pa, pb = old.get("pids") or {}, new.get("pids") or {}
    if isinstance(pa, list): pa = {x: "" for x in pa}
    if isinstance(pb, list): pb = {x: "" for x in pb}
    if pa or pb:
        merged = dict(pa)
        for k, v in pb.items():
            if v or k not in merged: merged[k] = v or merged.get(k, "")
        out["pids"] = dict(sorted(merged.items()))
    # Signals: ground truth is never displaced by a guess.
    sa = {s["did"]: s for s in (old.get("signals") or [])}
    for s in (new.get("signals") or []):
        prev = sa.get(s.get("did"))
        if prev and prev.get("confidence") == "ground-truth" \
                and s.get("confidence") != "ground-truth":
            warn(f"  keeping ground-truth name for {s.get('did')}, ignoring weaker claim")
            continue
        sa[s["did"]] = s
    if sa: out["signals"] = [sa[k] for k in sorted(sa)]
    for k in ("addressing", "protocol", "mode22", "mode22_evidence", "vehicle", "stats"):
        if new.get(k) and not out.get(k): out[k] = new[k]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("record")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    new = json.load(open(a.record))
    if new.get("notes") and "partial" in new["notes"]:
        print("note: this record came from a run that was stopped early")
    dst = target_for(new)
    old = json.load(open(dst)) if os.path.exists(dst) else {}
    warnings = []
    out = fold(old, new, warnings.append)
    for w in warnings: print(w)
    for k in LISTS + ("pids",):
        b, af = len(old.get(k) or []), len(out.get(k) or [])
        if b or af:
            assert af >= b, f"{k} shrank {b} -> {af}"
            print(f"  {k}: {b} -> {af}" + ("  (+%d)" % (af - b) if af > b else "  (unchanged)"))
    if a.dry_run:
        print("dry run; nothing written"); return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    order = ["vin_pattern", "year", "make", "model", "series", "vehicle", "addressing",
             "protocol", "headers", "blocks", "identifier_count", "map", "pids",
             "mode21_ids", "mode22", "mode22_evidence", "signals", "stats", "source", "notes"]
    ordered = {k: out[k] for k in order if k in out and out[k]}
    for k in out:
        if k not in ordered and out[k]: ordered[k] = out[k]
    with open(dst, "w") as f:
        json.dump(ordered, f, indent=2); f.write("\n")
    print(f"folded into {os.path.relpath(dst, ROOT)}")


if __name__ == "__main__":
    main()

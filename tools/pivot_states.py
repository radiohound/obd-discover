#!/usr/bin/env python3
"""Pivot captures by vehicle state, so an identifier can be read by its signature.

WHY NOT CORRELATION. Correlating a drive log against the nine logged anchors can only ever
find things that resemble those nine. Every signal identified on the F10 so far was pinned
a different way -- by what a value read in a KNOWN STATE. Oil pressure was settled because
it read atmospheric with the engine stopped; the LSU ceramic temperature because 780 C is
where a heated wideband element sits; the operating-hours counter because 4,424 hours
against 142,934 miles is 32 mph.

A state is a discriminator that costs nothing to arrange. At a cold soak with the ignition
on, every temperature sensor in the car reads the same number, every absolute pressure
reads barometric and every gauge pressure reads zero. That single condition splits the
address space before any driving happens -- and 74% of this project's BMW identifiers are
"constant" only because every capture so far was taken in one state.

    tools/pivot_states.py <dir-of-captures> [--vehicle WBA] [--did 224402]

Reads discover-*.json and discovered-*.csv, groups by the `state` each capture recorded,
and prints identifier x state. What the eye is looking for is a SIGNATURE:

    reads ambient cold, climbs fast, flat with road speed   -> coolant
    reads ambient cold, climbs slowly, lags coolant         -> oil
    reads ~1013 mbar at rest, rises with load               -> absolute manifold pressure
    reads 0 at rest, rises with load                        -> gauge boost
    moves when revved in neutral, not when rolling          -> engine-side, not road-side
    survives a key cycle                                    -> accumulator, not a live value
"""
import json, sys, os, csv, glob, argparse, collections

UNSPEC = "unspecified"


def load(root, vehicle=None):
    """(state, did) -> set of raw payloads, from every capture under root."""
    out = collections.defaultdict(set)
    states = collections.Counter()
    for f in glob.glob(os.path.join(root, "**", "discover-*.json"), recursive=True):
        try: d = json.load(open(f))
        except Exception: continue
        if vehicle and d.get("wmi") != vehicle: continue
        st = d.get("state") or UNSPEC
        states[st] += 1
        for e in d.get("detail") or []:
            for h in e.get("full_hits") or []:
                if isinstance(h, list) and len(h) == 2:
                    out[(st, h[0].upper())].add(h[1])
    # a drive CSV has no state of its own; it inherits the map written just before it
    for f in sorted(glob.glob(os.path.join(root, "**", "discovered-*.csv"), recursive=True)):
        st = UNSPEC
        near = sorted(glob.glob(os.path.join(os.path.dirname(f), "discover-*.json")))
        for m in near:
            if os.path.getmtime(m) <= os.path.getmtime(f):
                try: st = json.load(open(m)).get("state") or UNSPEC
                except Exception: pass
        try: rows = list(csv.reader(open(f)))
        except Exception: continue
        if len(rows) < 2: continue
        hdr, body = rows[0], rows[1:]
        states[st] += 0
        for i, h in enumerate(hdr):
            did = h.strip().split("@")[0].upper()
            if not did.startswith("22"): continue
            for r in body:
                if i < len(r) and r[i].strip(): out[(st, did)].add(r[i].strip())
    return out, states


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--vehicle", help="WMI, e.g. WBA")
    ap.add_argument("--did", help="show one identifier in full")
    ap.add_argument("--changed-only", action="store_true",
                    help="only identifiers that differ BETWEEN states — the useful ones")
    a = ap.parse_args()
    data, states = load(a.root, a.vehicle)
    seen = sorted({s for s, _ in data})
    print(f"  states present: {', '.join(seen)}")
    if seen == [UNSPEC]:
        print("  every capture is unlabelled, so nothing can be discriminated.\n"
              "  Tag captures with a vehicle state in the app and this becomes useful.")
    print()
    dids = sorted({d for _, d in data})
    if a.did:
        dids = [a.did.upper()]
    rows = []
    for did in dids:
        per = {s: sorted(data.get((s, did), ())) for s in seen}
        present = [s for s in seen if per[s]]
        if not present: continue
        # differs between states = the state moved it = identifiable
        firsts = {tuple(per[s]) for s in present}
        if a.changed_only and len(firsts) < 2: continue
        rows.append((did, per, present))
    print(f"  {len(rows)} identifiers" + (" that differ between states" if a.changed_only else ""))
    print()
    w = max((len(s) for s in seen), default=10)
    for did, per, present in rows[: (None if a.did else 60)]:
        print(f"  {did}")
        for s in present:
            v = per[s]
            shown = ", ".join(v[:4]) + (f"  (+{len(v)-4} more)" if len(v) > 4 else "")
            print(f"      {s:<{w}}  {shown}")
    if not a.did and len(rows) > 60:
        print(f"\n  ... {len(rows)-60} more; use --did to see one in full")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Compile vehicles/**/*.json into the single asset the APK ships.

Two reasons this is a build step and not a checked-in file:

  * One file per vehicle means two contributors adding two cars touch disjoint paths, so
    their pull requests never conflict. A shared blob would conflict on every PR.
  * The merge is where a record is validated. A file carrying more than VIN positions 1-8
    fails the build here, so a privacy mistake cannot reach an APK.

The shipped asset is the IDENTIFYING SUBSET, not the full map: pattern, model, headers and
256-DID blocks. Measured over 12 real captures that is 185 bytes per vehicle against 14 KB
for the full record -- 80x smaller -- so 2,000 vehicles cost 361 KB in the APK while the
full maps stay in the repo for humans to read.
"""
import json, sys, os, re, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC  = os.path.join(ROOT, "vehicles")
PATTERN_RE = re.compile(r"^[A-HJ-NPR-Z0-9]{1,8}$")   # VIN alphabet: no I, O or Q
REQUIRED   = ("make",)

def fail(path, msg):
    print(f"vehicles: {os.path.relpath(path, ROOT)}: {msg}", file=sys.stderr)
    return 1

def main(out_path):
    errors = 0
    by_pattern = {}
    by_model = collections.defaultdict(
        lambda: {"hdr": set(), "blk": set(), "pid": set(), "m21": set(), "m22": "",
                 "sig": {}})
    files = [os.path.join(d, f) for d, _, fs in os.walk(SRC)
             # *.map.json are the full identifier lists that sit beside a record. They
             # are for people, never shipped, and are not records -- skip them.
             for f in fs if f.endswith(".json") and not f.endswith(".map.json")]
    for p in sorted(files):
        try:
            r = json.load(open(p))
        except Exception as e:
            errors += fail(p, f"not valid JSON: {e}"); continue
        for k in REQUIRED:
            if not r.get(k): errors += fail(p, f"missing required field {k!r}")
        pat = (r.get("vin_pattern") or "").upper()
        if pat:
            # The privacy invariant, enforced. Positions 9-17 include the serial.
            if len(pat) > 8:
                errors += fail(p, f"vin_pattern is {len(pat)} chars; positions 9-17 "
                                  f"include the SERIAL and must never be committed")
                continue
            if not PATTERN_RE.match(pat):
                errors += fail(p, f"vin_pattern {pat!r} is not VIN alphabet (no I/O/Q)")
                continue
        for banned in ("vin", "mode09", "mode21", "vin_key"):
            if r.get(banned): errors += fail(p, f"field {banned!r} must not be contributed")
        make, model = r.get("make"), r.get("model") or ""
        hdr = [h for h in (r.get("headers") or []) if h]
        blk = [b[:4].upper() for b in (r.get("blocks") or []) if b]
        if pat and model:
            year = r.get("year")
            prev = by_pattern.get(pat)
            if prev and prev[1] != model:
                # Same pattern, two models: legitimate when the year differs, otherwise
                # one of the two records is wrong and a human should look.
                print(f"vehicles: NOTE {pat} maps to both {prev[1]!r} and {model!r}",
                      file=sys.stderr)
            by_pattern[pat] = [make, model, year]
        key = f"{make}|{model}" if model else make
        e = by_model[key]
        e["hdr"].update(hdr); e["blk"].update(blk)
        # A record writes pids as {pid: name} so a human reading the file on GitHub sees
        # "engine coolant temperature" rather than "0105". The SHIPPED asset keeps the
        # bare identifiers: the app already carries pid_standard.json and can name them
        # itself, so shipping the strings too would be the same text twice.
        pids = r.get("pids") or {}
        e["pid"].update(x.upper() for x in (pids.keys() if isinstance(pids, dict) else pids) if x)
        e["m21"].update(x.upper() for x in (r.get("mode21_ids") or []) if x)
        if r.get("mode22"): e["m22"] = r["mode22"]
        # SIGNALS SHIP. Everything else added to a record recently -- vPIC body/engine
        # attributes, probe counts -- stays in the repo for humans, because the app cannot
        # act on it. A named identifier is different: it lets a scan say "odometer, km"
        # about a car nobody has scanned before, which is the one thing this project says
        # it cannot do. Six fields per signal, a handful per vehicle.
        for sg in (r.get("signals") or []):
            did = (sg.get("did") or "").upper()
            if not did or not sg.get("name"): continue
            prev = e["sig"].get(did)
            # Ground truth outranks a guess; otherwise first writer wins.
            if prev and prev.get("c") == "ground-truth" and sg.get("confidence") != "ground-truth":
                continue
            e["sig"][did] = {k: v for k, v in
                             (("n", sg.get("name")), ("u", sg.get("unit")),
                              ("h", sg.get("header")), ("c", sg.get("confidence"))) if v}

    if errors:
        print(f"vehicles: {errors} problem(s); refusing to write {out_path}", file=sys.stderr)
        return 1
    out = {"patterns": {k: v for k, v in sorted(by_pattern.items())},
           "locations": {k: {kk: vv for kk, vv in
                             (("hdr", sorted(v["hdr"])), ("blk", sorted(v["blk"])),
                              ("pid", sorted(v["pid"])), ("m21", sorted(v["m21"])),
                              ("m22", v["m22"]), ("sig", v["sig"])) if vv}
                         for k, v in sorted(by_model.items())}}
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(out, f, separators=(",", ":"), sort_keys=True)
    nsig = sum(len(v.get("sig", {})) for v in out["locations"].values())
    print(f"vehicles: {len(files)} record(s) -> {len(out['patterns'])} VIN patterns, "
          f"{len(out['locations'])} model location sets, {nsig} named signal(s), "
          f"{os.path.getsize(out_path)} bytes")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1
                  else os.path.join(SRC, "..", "app/src/main/assets/vin_patterns.json")))

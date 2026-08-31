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

ONE EXCEPTION, ADDED DELIBERATELY: the identifier lists in *.map.json now ship too, in a
compact form. A blind sweep asks 256 identifiers per block to find about 25, and it runs
that way even on a car this project has already mapped. Asking the identifiers we measured
last time, by name, produces a working drive-log plan in 1.7 minutes on a BMW where the
blind sweep plus recon takes 24 -- fourteen-fold, measured at the rates real runs achieve.

The lists carry NO payloads; what a vehicle returned is never committed, and that rule is
unchanged. Grouped by header with the 22 prefix implied, all six current records are 15.5 KB
against 544 KB of DTC text already in the APK. Around 200 vehicles this reaches ~516 KB and
fetching a matched record at run time becomes the better answer -- but that needs a network,
and a garage often has none.

THIS SETS THE ORDER OF THE WORK AND NEVER ITS BOUNDS. Nothing here lets the sweep skip
anything: a record says what to ask first, never what to leave out. Absence from a record is
weak evidence, and this project has already been caught by it, on a BMW F10 that answered on
three blocks the community list did not contain.
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
        LEVELS = ("ground-truth", "correlated", "weak", "inferred",
                  "community-published", "guess")
        for sg in (r.get("signals") or []):
            did = (sg.get("did") or "").upper()
            if not did or not sg.get("name"): continue
            # A confidence level has to mean the same thing in every record, or the field
            # is decoration. `correlated` in particular is a claim about a correlate run
            # and has to carry the numbers that back it.
            conf = sg.get("confidence")
            if conf not in LEVELS:
                errors += fail(p, f"signal {did}: confidence {conf!r} is not one of {LEVELS}")
                continue
            if conf == "correlated" and not (sg.get("r") and sg.get("samples")):
                errors += fail(p, f"signal {did}: 'correlated' must record `r` and `samples`")
                continue
            if conf in ("correlated", "weak") and sg.get("r") is not None:
                floor = 0.90 if conf == "correlated" else 0.60
                if float(sg["r"]) < floor:
                    errors += fail(p, f"signal {did}: r={sg['r']} is below the {conf} floor "
                                      f"of {floor} -- correlate.py's own threshold")
                    continue
            if not sg.get("verified"):
                errors += fail(p, f"signal {did}: needs `verified` saying how it is known")
                continue
            prev = e["sig"].get(did)
            # Ground truth outranks a guess; otherwise first writer wins.
            if prev and prev.get("c") == "ground-truth" and sg.get("confidence") != "ground-truth":
                continue
            e["sig"][did] = {k: v for k, v in
                             (("n", sg.get("name")), ("u", sg.get("unit")),
                              ("h", sg.get("header")), ("c", sg.get("confidence"))) if v}

    # A record whose blocks were measured in different states is a legitimate thing to
    # contribute -- a map assembled over several drives is the point of resumable mapping --
    # but it is not the same artefact as one taken in a single sitting, and the difference
    # has to be visible to whoever reads it. Reported, never refused.
    for p in sorted(files):
        try:
            r = json.load(open(p))
        except Exception:
            continue
        states = {b.get("state") for b in (r.get("detail") or [])
                  if isinstance(b, dict) and b.get("state")}
        if len(states) > 1:
            print(f"vehicles: {os.path.relpath(p, ROOT)}: assembled across "
                  f"{len(states)} vehicle states ({', '.join(sorted(states))}); "
                  f"identifiers in it are not all answers to the same question")

    # IDENTIFIERS THE RECORD IS STILL ASKING ABOUT. open_questions is prose, but it names
    # its subjects inline -- "224A4B needs a DENSE log", "2258BA wants MAF-derived power" --
    # so the DIDs come out with a regex and no schema change. They ship because the focused
    # log needs them: the point of narrowing a log is resolution on what is UNRESOLVED, and
    # a list of named signals is by definition the resolved half.
    for p in sorted(files):
        try:
            r = json.load(open(p))
        except Exception:
            continue
        key = f"{r.get('make','')}|{r.get('model','')}"
        if key not in by_model:
            continue
        oq = set()
        for q in (r.get("open_questions") or []):
            oq.update(d.upper() for d in re.findall(r"\b22[0-9A-Fa-f]{4}\b", str(q)))
        if oq:
            by_model[key]["oq"] = sorted(oq)

    if errors:
        print(f"vehicles: {errors} problem(s); refusing to write {out_path}", file=sys.stderr)
        return 1
    # The measured identifier lists, from the *.map.json beside each record.
    for p in sorted(f for f in
                    (os.path.join(d, f) for d, _, fs in os.walk(SRC) for f in fs)
                    if f.endswith(".map.json")):
        try:
            m = json.load(open(p))
        except Exception as e:
            errors += fail(p, f"unreadable: {e}"); continue
        key = f"{m.get('make','')}|{m.get('model','')}"
        if key not in by_model:
            errors += fail(p, f"no record for {key}; a map must sit beside its record")
            continue
        packed = {}
        for hdr, ids in (m.get("identifiers") or {}).items():
            bad = [i for i in ids if len(i) != 6 or not i.upper().startswith("22")]
            if bad:
                errors += fail(p, f"{hdr}: not 6-char Mode-22 identifiers: {bad[:3]}")
                break
            # Suffixes only, concatenated. The 22 is implied and the width is fixed, so
            # no separators are needed and the reader splits on four.
            packed[hdr.upper()] = "".join(sorted(i.upper()[2:] for i in ids))
        else:
            if packed:
                by_model[key]["ids"] = packed

    out = {"patterns": {k: v for k, v in sorted(by_pattern.items())},
           "locations": {k: {kk: vv for kk, vv in
                             (("hdr", sorted(v["hdr"])), ("blk", sorted(v["blk"])),
                              ("pid", sorted(v["pid"])), ("m21", sorted(v["m21"])),
                              ("m22", v["m22"]), ("sig", v["sig"]),
                              ("oq", v.get("oq")), ("ids", v.get("ids"))) if vv}
                         for k, v in sorted(by_model.items())}}
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(out, f, separators=(",", ":"), sort_keys=True)
    nsig = sum(len(v.get("sig", {})) for v in out["locations"].values())
    nids = sum(len(h) // 4 for v in out["locations"].values()
               for h in (v.get("ids") or {}).values())
    print(f"vehicles: {len(files)} record(s) -> {len(out['patterns'])} VIN patterns, "
          f"{len(out['locations'])} model location sets, {nsig} named signal(s), "
          f"{nids} measured identifier(s), "
          f"{os.path.getsize(out_path)} bytes")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1
                  else os.path.join(SRC, "..", "app/src/main/assets/vin_patterns.json")))

#!/usr/bin/env python3
"""Turn a discover-*.json map into a vehicles/ record.

Bridges captures taken before the app could emit records directly. It carries over ONLY
the identifying subset -- headers, 256-DID blocks, addressing -- and never copies
`vin_key`, `mode09` or `mode21`, which hold a hash, the VIN itself and possible serials.

A map has no VIN pattern in it (the app stores a SHA-256 of the VIN, by design) and no
model, so both are left blank for a human to fill in. A record with neither is still
useful: it contributes on-car locations for the make, which is ground truth that the
community lists are not.
"""
import json, sys, os

_STD = None
def _named(pids):
    """{pid: standard name}, so the committed record reads without a lookup table."""
    global _STD
    if _STD is None:
        import os
        p = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "app/src/main/assets/pid_standard.json")
        _STD = json.load(open(p))["mode01"] if os.path.exists(p) else {}
    out = {}
    for x in sorted(pids):
        e = _STD.get(x[2:])
        out[x] = (f"{e['n']} ({e['u']})" if e and e.get("u") else (e["n"] if e else ""))
    return out


def record(mapfile, make=None, model="", pattern="", year=None):
    d = json.load(open(mapfile))
    hdrs = d.get("speaks_mode22") or d.get("headers_targeted") or []
    blocks = sorted({b["name"][:4].upper() for b in d.get("blocks", []) if b.get("name")})
    mk = make or (d.get("hints") or {}).get("make") or d.get("preset") or ""
    if mk == "generic": mk = ""
    r = {"vin_pattern": pattern, "year": year, "make": mk, "model": model,
         "addressing": d.get("addressing", ""), "protocol": d.get("protocol", ""),
         "headers": [h for h in hdrs if h], "blocks": blocks,
         # A non-CAN car has no blocks; what it answers is these lists. Excluding them
         # would drop the Highlander, whose Mode-22 silence is a real finding.
         "pids": _named(d.get("mode01") or []), "mode21_ids": d.get("mode21_ids") or [],
         "mode22": d.get("mode22_verdict", ""),
         "mode22_evidence": d.get("mode22_evidence", ""),
         "source": f"obd-discover capture {os.path.basename(mapfile)}"}
    if d.get("aborted"):
        # An aborted run's block list is real but incomplete; say so rather than let it
        # look like a full map of the vehicle.
        r["notes"] = "run was stopped early; block list is partial"
    return {k: v for k, v in r.items() if v not in ("", None, [])}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: capture_to_record.py discover-*.json [make] [model] [pattern] [year]")
    a = sys.argv[1:]
    print(json.dumps(record(a[0], *(a[1:5])), indent=2))

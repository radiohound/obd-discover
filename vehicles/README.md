# vehicles/

One file per real vehicle someone scanned. This is the project's own database, and it
exists because the public sources cannot supply what is in here.

## Why not just use NHTSA vPIC

vPIC can turn a VIN into a model, but only for some makes, and it knows nothing about
which identifiers a car actually answers. Measured on 2026-08-28: brute-forcing VIN
prefixes against `DecodeVINValuesBatch` names a model for **31% of WMIs** (14 of a random
45). The other 69% are not slow, they are unreachable — Acura's `19U` returns nothing at
prefix depth 5, 6, 7 or 8, because vPIC decodes from a pattern table and enumeration only
works when a make's pattern keys on the first two VDS characters. vPIC's `DecodeWMI` is
also silent for most imports: it has no answer for `JTM`, `WBA`, `KM8` or `JF2`.

A single scan from this app supplies all of it at once, from the car itself.

## What one file looks like

    vehicles/Chevrolet/1GC4YPE-2021.json

```json
{
  "vin_pattern": "1GC4YPE8",     // VIN positions 1-8. NEVER more.
  "year": 2021,                  // VIN position 10
  "make": "Chevrolet",
  "model": "Silverado 2500HD",
  "addressing": "29-bit",
  "protocol": "7",
  "headers": ["DB33F1", "DA18F1", "DA28F1", "DA11F1"],
  "blocks":  ["2200", "2240", "2243", "..."],
  "source":  "obd-discover 0.1",
  "notes":   "optional, free text"
}
```

`blocks` are 256-identifier ranges, not individual DIDs. A Silverado answering 1,929 DIDs
is 40 blocks, and blocks are what the scanner actually consumes to reorder a run.

## What must never be in one of these files

**VIN positions 9 through 17.** Position 9 is a check digit, 11 is the assembly plant, and
**12-17 are the serial — the field that identifies one specific car.** Positions 1-8 plus
the year describe a model and trim that millions of vehicles share; they cannot be traced
to a person or a car. The merge script rejects any record carrying more than 8 characters
in `vin_pattern`, so a mistake here fails the build rather than shipping.

Also excluded: raw Mode-09 and Mode-21 payloads. A Mode-09 record *is* the VIN, and an
unidentified one-byte Mode-21 value may be a serial. Contribute which identifiers
answered, never what they returned.

## Adding your car

1. Scan it with the app, then use **Export → contribute**.
2. Drop the file in `vehicles/<Make>/`.
3. Open a pull request.

One vehicle per file, so two contributors never touch the same file and pull requests
never conflict.

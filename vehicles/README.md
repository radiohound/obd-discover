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

## What else a record can hold

The shipped asset stays small — patterns, locations, and named signals. Everything below
is repo-side, read by people rather than by the app, so a record should carry what was
measured rather than summarising it in prose.

```json
{
  "vehicle": { "body": "Sedan/Saloon", "cylinders": "6", "displacement_l": "3.0",
               "fuel": "Gasoline", "doors": "4" },

  "signals": [                              // identifiers somebody NAMED, and how
    { "did": "221700", "header": "7DF", "name": "odometer", "unit": "km",
      "confidence": "ground-truth",
      "verified": "read against the dashboard, and again as +5 km over a 17-minute drive" }
  ],

  "detail":  [ ... ],                       // WHICH identifiers answered, per block
  "stats":   { "probes": 17441, "identifiers_found": 1929 },
  "mode21_mirrors_mode01": [ "2101", ... ], // Mode-21 ids that only repeat Mode 01
  "mode09_bitmap": "01FC000000",
  "mode21_claimed_no_reply": [ ... ]        // claimed by the bitmap, never answered
}
```

### signals is the one that matters

The README says naming is the limit this app cannot pass — it finds which identifiers
answer, not what they mean. That is true of one scan and it does not have to stay true of
the project. `221700` on a BMW F10 is the odometer in kilometres, confirmed against a
dashboard reading and then against a 17-minute drive. Because that is a field and not a
sentence, every later F10 scan can name it without repeating the work.

**`confidence` is not decoration.** Use `ground-truth` only when a number was watched
against something real — a dashboard, a fuel receipt, a measured drive. Anything else is a
guess and should say so, because a wrong name is worse than no name: it stops the next
person looking.

`notes` is for prose that is genuinely prose. If a fact has a shape, give it a field.

## Cars that are not CAN

A K-line car has no headers and no blocks, and is still worth contributing — the
Highlander's record is the richer one:

```json
{
  "make": "Toyota", "model": "Highlander",
  "protocol": "A3", "addressing": "none — A3 is not CAN",
  "pids": ["0101", "0103", "..."],          // 20 Mode-01 PIDs
  "mode21_ids": ["2100", "2101", "..."],    // 63 Mode-21 identifiers
  "mode22": "SILENT",
  "mode22_evidence": "no reply to any of 23 probes"
}
```

`mode22` is the single most valuable field a K-line car produces, because it is why the
next scan need not spend a sweep rediscovering the same silence. It is recorded as
**evidence, not as permission to skip.** One car's silence is not every car's, and this
project's rule is that observations reorder a scan and never restrict it — the BMW is why:
probing only what was written down would have lost 358 of 462 identifiers.

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

1. Scan it with the app, then tap **ADD VEHICLE** (the blue button).
2. It opens a **draft** issue with the record filled in. Read it and tap Submit —
   nothing is posted until you do.

One vehicle per file, so two contributors never touch the same file and pull requests
never conflict.

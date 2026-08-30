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

  "pids": {                                 // Mode-01, with the standard's own names
    "0105": "engine coolant temperature (degC)",
    "010C": "engine speed (rpm)"
  },

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

### confidence is not decoration

A wrong name is worse than no name — it stops the next person looking. So every signal says
how well it is known, on a ladder the project already has thresholds for:

| level | what it means |
| :--- | :--- |
| `ground-truth` | a value was watched against something real — a dashboard reading, a fuel receipt, a measured distance |
| `correlated` | `obd_scan correlate` scored `r >= 0.90` against a known anchor over at least 30 samples. Record `against`, `r` and `samples` |
| `weak` | `r >= 0.60`, or fewer than 30 samples. A lead, not a finding |
| `inferred` | identified by reasoning rather than measurement — a value that sits where physics says it should. Say what the reasoning was |
| `community-published` | named in a third-party map with no stated derivation. Below `inferred`: a name somebody wrote down, not a measurement |
| `guess` | anything else, and it should say so |

The thresholds are `correlate.py`'s own, mirrored in `Triage.kt` as `MIN_R_STRONG` (0.90),
`MIN_R_WEAK` (0.60) and `MIN_SAMPLES` (30), so a claim here means the same thing it means in
a correlate report.

**You never have to publish your log to prove a signal.** `r`, the sample count and the
anchor are claims *about* the data, not the data — and a delta is shareable where an
absolute is not. "Rose 41 over a 24.5-minute drive, matching distance travelled" proves an
odometer and tells nobody your mileage. Run `correlate` on your own machine, keep the RAW
export, publish the conclusion.

### Citing a community map

A third-party DID map may be someone's reverse-engineering of a manufacturer's own
diagnostic tables. That raises two problems and they have the same answer.

**Licensing.** This repository is MIT. A translated label table extracted from a
manufacturer's diagnostic software is not something the publisher could license, so it is
not something this project can relicense. Bulk-importing one would be republishing text
nobody in the chain owns.

**Provenance.** An anonymous table with no stated derivation cannot be audited. It may be
accurate — and may still be someone else's property.

So the rule here is: **import the facts you verified, not the table.** That a given
identifier reads coolant temperature as `raw × 0.75 − 48` is a fact about a machine,
established by measurement on a car you have. Facts about how a device behaves are not
anyone's property; a table of translated labels might be. Record the rows your own logs
support, cite where you first saw the claim, and leave the rest where you found it.

A cited row says all three things: what the map claimed, what this project measured, and
that the two agree.

Verification is by **reproduction, not audit**: someone with the same model drives, gets the
same result, and the signal is confirmed on two cars. That is stronger evidence than one
person's CSV, because it rules out something peculiar to one vehicle.

`pids` carries the names because a record is read by a person on GitHub before anything
else reads it, and `"0105"` says nothing to a reader without the table open beside it. The
shipped asset keeps the bare identifiers — the app already has `pid_standard.json` and can
name them itself, so shipping the strings too would be the same text twice in one APK.

Mode-21 identifiers stay bare. They are manufacturer-specific and there is no standard to
name them from — which is what [`signals`](#signals-is-the-one-that-matters) is for.

`notes` is for prose that is genuinely prose. If a fact has a shape, give it a field.

## Folding a record in

Use the tool, not an editor:

```bash
tools/add_record.py contributed.json --dry-run   # show what would change
tools/add_record.py contributed.json             # fold it
```

**A contributed record describes one run, and a stopped run is partial.** The BMW's
aborted sweep found 16 blocks where its record already held 17, so replacing a record with
a newer one silently loses whatever the newer run did not reach. Every list is unioned and
nothing may shrink — the script asserts it.

It also refuses to merge two vehicles: if `vin_pattern`, `model` or `year` disagree, it
stops rather than producing one record describing two cars. And a `ground-truth` signal is
never displaced by a weaker claim.

Folding a deliberately partial retest of the Silverado — 9 blocks of 40, one header of
four — reports what the incoming run missed and keeps everything:

    incoming blocks is missing 31 the record already has (partial run?) -- keeping both
    blocks: 40 -> 40  (unchanged)
    pids:    0 -> 2   (+2)

which is the point: a short retest can only ADD.

## Identifying a field: state, not correlation

Correlating a drive log against the nine logged anchors can only find things that resemble
those nine. Every signal identified in this project so far was pinned a different way — by
what a value read in a **known state**. Oil pressure was settled because it read atmospheric
with the engine stopped. The LSU ceramic temperature because 780 °C is where a heated
wideband element sits. The operating-hours counter because 4,424 hours against 142,934 miles
is 32 mph.

A state costs nothing to arrange and discriminates a whole class at once. **At a cold soak
with the ignition on, every temperature sensor in the car reads the same number, every
absolute pressure reads barometric, and every gauge pressure reads zero.**

So tag each capture with the state the car was in — one tap in the app — and use
`tools/pivot_states.py` to read identifiers by their signature across states:

```bash
tools/pivot_states.py <captures-dir> --vehicle WBA --changed-only
```

| signature across states | reads as |
| :--- | :--- |
| ambient when cold, climbs fast, flat with road speed | coolant |
| ambient when cold, climbs slowly, lags coolant | oil |
| ~1013 mbar at rest, rises with load | absolute manifold pressure |
| 0 at rest, rises with load | gauge boost |
| moves when revved in neutral, not when rolling | engine-side, not road-side |
| survives a key cycle | an accumulator, not a live value |

The states worth capturing, in order of what they buy for what they cost:

1. **key on, engine off (cold)** — 2 minutes, and the most discriminating of all
2. **cold start, warming up** — separates coolant from oil from intake by their time constants
3. **warm idle** — a baseline, and where electrical load shows
4. **stationary stimulus** — revving in neutral separates engine-side from road-side, which no drive can
5. **driving** — boost, gear, road speed
6. **shutdown / re-key** — accumulators versus values that reset

**A "constant" is usually just a field nothing has moved yet.** 74% of this project's BMW
identifiers are constant across every capture taken — and every one of those captures was
made in the same state: warm engine, gentle driving.

Labelling also makes captures **compose**. Two people's cold soaks on the same model confirm
each other; two warm drives cannot, because a field constant in both may simply be untouched.

## The full identifier list

`blocks` are 256-wide ranges, so seventeen of them stand in for hundreds of real
identifiers. The actual list lives beside the record:

    vehicles/BMW/5-Series.json          the record       17 blocks
    vehicles/BMW/5-Series.map.json      the full map    572 identifiers

The record names the map in its `map` field and counts it in `identifier_count`. Maps are
never shipped in the APK — the app works from blocks — and the merge script skips them.

**Identifiers only, never what they returned.** A map lists which addresses answered. The
values they gave back are not committed, because an unidentified Mode-22 value can be a
serial or an odometer as easily as anything else. This is the same rule that keeps Mode-09
and Mode-21 payloads out of a shared capture.

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

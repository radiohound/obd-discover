# Third-party notices

The application source is MIT — see [LICENSE](LICENSE). This file records everything else:
code derived from another project, and bundled data files that keep their own licences.

It is the single source for these notices. `app/src/main/assets/ATTRIBUTION.txt`, which the
app shows under **sources & licence**, is generated from this file at build time, so the two
cannot disagree. Edit this one.


## obd-gauge-cluster — the project this app is built to feed

  https://github.com/cheeseprince/obd-gauge-cluster
  MIT License, Copyright (c) 2026 Alan Young

Its `obd_scan` tooling defined the method this app follows — census, sweep, drive log,
correlate — and the capture formats it writes. `discover-*.json` is read by
`obd_scan sweep --blocks-from` unmodified, and `discovered-*.csv` by `obd_scan correlate`.
Producing those from a phone rather than a laptop is the whole purpose here.

Directly derived: `Obd.decodeAnchor` is a port of `stages._decode_anchor`, `Obd.ANCHORS`
follows `catalog.ANCHORS`, and `Triage` mirrors `correlate.py`'s interpretations, sentinel
set and thresholds — deliberately, so this app's advisory reading cannot quietly disagree
with the authoritative one.

Also derived: the Mode-22 decodes recorded against BMW 5 Series in `vehicles/` --
224402 oil temperature, 22586F oil pressure, 2258BA crank torque and 224517 reference
torque -- come from that project's curated F10 profile, `src/vehicles/bmw_f10_535i.cpp`.
The identifiers were found independently by this app's own sweep; what obd-gauge-cluster
supplied is what they MEAN and how to scale them, which is the harder half.

If you scan a vehicle with this app, that is the project to send results to.

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.


## obdb_hints.json, obdb_supported.json, obdb_models.json — CC BY-SA 4.0

  Adapted from the OBDb vehicle signal database — https://github.com/OBDb
  Licence: CC BY-SA 4.0 — https://creativecommons.org/licenses/by-sa/4.0/

What was taken: locations only. Which header answers, which service, which 256-DID block,
and which DIDs are known to respond, plus the list of models OBDb holds per-model data for.
Not the signal definitions, names or decode formulas.

As adaptations these files remain CC BY-SA 4.0. Redistribute them, modified or not, and you
must credit OBDb and keep the same licence.

**This obligation travels with the APK.** CC BY-SA applies to those data files, not to the
software that reads them — the Kotlin sources are MIT — but redistributing the APK
redistributes the files, so this notice must go with it. That is why the app displays it.


## dtc_generic.json — MIT

  Generic SAE J2012 trouble-code descriptions, from the dtc-database project —
  https://github.com/Wal33D/dtc-database
  Licence: MIT.

Generic codes only. Manufacturer-specific codes are omitted deliberately: their meaning is
defined by each manufacturer, so a shared table cannot name them, and this app shows their
structure instead of guessing.


## wmi_to_make.json — public domain

  Derived from the NHTSA vPIC database — https://vpic.nhtsa.dot.gov
  Public domain (a work of the U.S. Government).


## pid_standard.json — no licence attaches

  Mode-01 PID and Mode-09 info-type names, from SAE J1979 / ISO 15031-5.

These are the identifiers and meanings the emissions standard defines, so the mapping is a
statement of that standard rather than anyone's authored work. The standard is cited because
a reader should be able to check it. Mode-22 identifiers are NOT here and cannot be: they
are manufacturer-specific by definition, which is what this app exists to map.


## This project's own data — MIT

  dtc_supplement.json
      Trouble codes met in the field that the bundled SAE table does not cover.
      Community-reported and shown as such, never as authoritative definitions.

  vin_patterns.json
      Generated at build time from vehicles/ by tools/merge_vehicles.py.

  vehicles/
      Records contributed by people who scanned their own cars.

Part of this repository and covered by the MIT licence, like the source.

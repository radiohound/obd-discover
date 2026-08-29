# OBD Discover

Map an unlisted vehicle's enhanced OBD-II data from an Android phone, with no laptop.

Most OBD tools can only read parameters somebody already wrote down for your car. This one
finds them: it probes the vehicle's Mode-22 address space — or, on a pre-CAN car, its Mode-01,
Mode-09 and Mode-21 spaces — reports what answers, logs it during a drive, and exports the
result in the formats
[obd-gauge-cluster](https://github.com/cheeseprince/obd-gauge-cluster)'s `obd_scan` already
reads — so a capture can be contributed without editing anyone's source.

**Built to feed [obd-gauge-cluster](https://github.com/cheeseprince/obd-gauge-cluster)**
by Alan Young (MIT). Its `obd_scan` tooling defined the method — census, sweep, drive log,
correlate — and the capture formats this app writes; parts of the anchor decoding and the
advisory drive check are ported from it directly. If you scan a vehicle with this, that is
the project to send the results to.

**It is read-only.** It sends Mode 01 (current data), Mode 03 (stored codes), Mode 09 (vehicle
information), Mode 22 (read data by identifier) and Mode 21 (read data by local identifier).
Nothing else. It cannot write to a vehicle, and it cannot clear trouble codes — Mode 04 is
deliberately absent, because clearing codes also resets the readiness monitors and fails an
emissions test until a full drive cycle has run.

Mode 21 is the one that needs justifying, so it is justified rather than assumed. Every Mode-21
request this app can build is exactly two bytes, and the check that enforces that validates
length as strictly as it validates the service byte. That matters because ISO 14230-3 puts the
transmission-mode field — the one that asks an ECU to stream a value repeatedly — in the third
byte, which a two-byte request cannot reach. On KWP2000 that is a guarantee from the standard.
On ISO 9141-2 no standard we have read defines Mode 21 at all, so the same reasoning is by
analogy rather than by specification, and there it is **opt-in per vehicle** with that
distinction stated on screen before you tick it.

## What it does

**START** — scan, connect, handshake. Three lights show which step is which, so a failure
names itself instead of collapsing into "not connected".

**CAPTURE** — identifies the vehicle, maps its blocks if it has not seen it before, then logs
a drive. One button:

1. Detects the OBD protocol (`AT SP0`, then each of the nine explicitly if that finds nothing)
2. Reads the VIN, resolves the manufacturer and the model year offline, reads stored trouble codes
3. On CAN: asks for the DIDs already known for that make, by name, plus the five UDS
   identification DIDs (`F187` spare part, `F18C` ECU serial, `F190` VIN, `F191` hardware,
   `F195` supplier software) on every header that answered; sweeps the 256 candidate Mode-22
   blocks for anything the tables do not know about; then fully sweeps every block that
   answered
4. On ISO 9141-2 or KWP2000: reads the Mode-01 and Mode-09 support bitmaps, probes whether
   Mode 22 answers at all, and — opt-in on ISO 9141-2 — sweeps all 256 Mode-21 local
   identifiers. Mode-22 block discovery is the part that needs CAN ids; the service itself
   is measured rather than assumed
5. Logs whatever was found during a drive, alongside the nine generic anchors

With the online lookup on, it also **names the DIDs OBDb documents for that model** —
fetching that model's signal definitions from OBDb at run time and decoding them, so
`22005C` on a Silverado reads *"Engine oil temperature: 52 celsius"* rather than `5C`. Only
a minority: 55 signals against the 1,929 DIDs a sweep of that truck finds. What leaves the
phone is a repository name — no VIN, nothing about the vehicle. The definitions are fetched
from OBDb rather than bundled, so nothing is redistributed and the file is 19 KB for one
model instead of ~10 MB for all of them.

It does **not** identify the model. The VIN's first three characters (the WMI) name the
manufacturer, and character 10 gives the model year — those are what the bundled NHTSA table
covers. The model itself is encoded in characters 4–8, which every manufacturer assigns
differently and no free table decodes. So a scan reports "2006 Toyota", never "2006
Highlander", and the DID hints are selected by manufacturer rather than by model.

A block is only reported if the full sweep found something in it. A single spurious positive
during the fast pass is enough to invent a block that does not exist — one appeared on a BMW,
in one run out of three, and would have travelled into a shared capture. If the sweep of all
256 offsets then finds nothing, the vehicle has contradicted itself and the block is dropped.

**CONTROLS TEST** — you operate the air conditioning, lights, revs, steering and brakes to a
prompt while it logs. Fields with no relationship to speed or temperature can only be
identified this way. Nothing is sent to the car beyond the same read requests.

**EXPORT SCRUBBED / EXPORT RAW** — a zip via the share sheet. Green is safe to attach to a
public issue; orange keeps the VIN, for your own records. Both are scoped to the car in front
of you, matched on its VIN key, so a phone that has scanned two vehicles cannot bundle the
wrong one's map.

Along the way it also:

- **Names stored trouble codes.** 9,415 generic SAE J2012 descriptions ship with the app, so a
  code reads as "Cylinder 3 Misfire Detected" and not just `P0303`. Manufacturer-specific codes
  are shown with their structure decoded and no invented meaning.
- **Reads calibration IDs and CVNs** (Mode 09). On a car where block discovery cannot run these
  are most of what distinguishes this vehicle's capture from another of the same year and model
  — they name the exact firmware, and a reflash changes them.
- **Remembers a car it has mapped.** The map is keyed to a hash of the VIN, so plugging into a
  known vehicle skips straight to the drive log instead of re-mapping for ten minutes. **Re-map**
  overrides that when firmware has changed.
- **Appends to a short drive.** `correlate` needs 30 samples; a drive that stopped early is
  continued into the same file rather than stranded in a second one — and only if the columns
  match exactly.
- **Shows the adapter log**, so a failure to connect names itself rather than being a mystery.

## What it already knows

The app carries offline tables so it can aim a scan before it has learned anything about the
car in front of it:

| Table | Covers | Source |
|---|---|---|
| Documented block and header locations | 2,218 rows across **58 makes** | OBDb (CC BY-SA 4.0) |
| DIDs known to answer | 13,723 across **44 makes** — 2,039 for BMW alone, 1,075 Volkswagen, 1,001 Audi | OBDb (CC BY-SA 4.0) |
| Model-specific locations | 147 models across 33 makes | OBDb (CC BY-SA 4.0) |
| WMI → manufacturer | 492 codes | NHTSA vPIC (public domain) |
| Generic trouble codes | 9,415 — 7,387 P, 1,230 U, 498 C, 300 B | dtc-database (MIT) |

Where a make has model-specific data, the app reports which model the vehicle **answers
like** — matched against the locations that actually responded, not decoded from the VIN.
122 of the 147 models have a unique signature. A model is only claimed when every location
it documents was found, and a tie is reported as a tie, because some ties are real: a Bolt
EV and a Bolt EUV are one platform.

**Hints reorder a sweep; they never restrict one.** This matters more than the table sizes. On a
BMW F10 with six real Mode-22 blocks, three were absent from the community list — so a scan that
trusted the tables would have reported two thirds of the car and looked confident doing it.
Known locations are probed first because they are cheap, and then all 256 blocks are probed
anyway. The same rule held on the Highlander: its ECU published support bitmaps claiming 73
Mode-21 identifiers, and `2170` answered without appearing in any of them.

## How long it takes

Longer than feels right, and the app used to be bad at saying so. Both numbers are measured,
not estimated:

| | |
|---|---|
| Discovery | **15–20 minutes** parked. A BMW F10 is 7,695 probes, a Ford Ranger 11,220. |
| Drive | **22 minutes minimum**, and that is a floor rather than a target. |

The drive floor is arithmetic, not impatience. `correlate` needs 30 samples, and a row costs
one request per DID found plus the seven anchors — 575 requests on that BMW, about 43 seconds
a row. Thirty rows is 22 minutes of driving.

Both phases show a progress bar and a remaining-time estimate from the run's own measured
rate. The drive bar turns green at 30 rows. A scan stopped early is not wasted — the blocks
already swept are written out — but it is worth knowing that on a rich car most of the DIDs
are in the last few blocks.

## Privacy

**One optional network call, off by default.** Everything else — scanning, decoding, the
bundled tables, export — works with no network at all, and the app held no `INTERNET`
permission until this was added.

Ticking **Look up the model online** sends the **first 10 characters** of the VIN to
[NHTSA vPIC](https://vpic.nhtsa.dot.gov) to resolve the model and year:

| VIN positions | what they are | sent? |
| :-- | :-- | :-- |
| 1–3 | manufacturer (WMI) | yes |
| 4–9 | model, body, engine (VDS) | yes |
| 10 | model year | yes |
| 11 | assembly plant | **no** |
| 12–17 | **serial number — identifies one vehicle** | **no** |

Ten was chosen by measuring: it is the shortest prefix that still returns both model and
year. Thousands of cars share any given ten characters, so what leaves the phone describes a
model rather than a car. Answers are cached by that prefix, so a model is looked up once
ever — a second car of the same model and year costs no request.

A bundled table was considered and rejected. Size was not the obstacle: patterns for the 147
models here would be 30–60 KB, the smallest asset in the app. Valid VIN patterns are sparse
and cannot be discovered by enumeration — 42 constructed BMW prefixes were tried and none
decoded, while one taken from a real vehicle did. NHTSA does not publish the pattern table
through its API, and a copy extracted from the full database goes stale as model years add
patterns.

**No capture file contains the VIN.** Captures carry the 3-character WMI (which identifies a
manufacturer, not a car) and the first 4 bytes of a SHA-256 of the VIN, used only to recognise a
vehicle the app has mapped before. The scrubbed export drops even that hash, because someone
holding a candidate VIN could hash it and test for a match.

The single exception is deliberate and is the point of the button: **EXPORT RAW writes the VIN**
into the bundle's `README.txt`, because that export exists for the owner's own records. The file
says so at the top, in those words. Use the scrubbed export for anything you post.

## What it cannot do

- **Name anything.** It finds which DIDs answer, not what they mean. Naming needs a drive log
  and `obd_scan correlate`, plus human work. This is the real limit, and no amount of scanning
  removes it.
- **Mode-22 block discovery on a pre-CAN bus.** Walking module by module needs CAN ids, which
  ISO 9141-2 and KWP2000 do not have. The *service* is a separate question and is probed rather
  than assumed — on a 2006 Highlander nothing answered, and because that ECU sends no refusal
  codes at all, the honest verdict there is undecided rather than no.
- **Reach a module the adapter cannot address.** Some documented locations need extended
  addressing (`AT CEA`/`AT CRA`); the app says which ones it is skipping instead of reporting
  them as empty.
- **Classic-Bluetooth adapters.** BLE only. A PIN-pairing ELM327 will not be found.
- **Clear a trouble code.** Mode 04 is absent by design, as above.

## Hardware

Any BLE ELM327 adapter. Four GATT profiles are tried (`18F0/2AF0/2AF1`, `FFF0`, `FFE0`,
Nordic UART), which covers most clones. Devices whose names are unfamiliar can be selected by
hand from the scan list — the name list ranks candidates, it does not gate them.

Verified on a Vgate iCar Pro BLE 4.0.

An Android phone running 8.0 or later (API 26). It does not have to be your daily phone, and
there is a reason to prefer that it isn't: a scan wants to sit in the car with the ignition on,
and a cold-start capture wants the car left alone for six hours beforehand. A used handset is
around $100, which together with the adapter is a complete rig — and it rides in the footwell
on its own battery, which is not something you would ask of a laptop.

## Installing

No release APK is published yet, so build it and push it over USB:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs Android 8.0 or later (API 26). If you sideload an APK rather than using `adb`,
Android will ask you to allow installs from whichever app you downloaded it with.

**Permissions it will ask for, and why:**

| Permission | Why |
| :--- | :--- |
| Nearby devices / Bluetooth | to find and talk to the adapter |
| Location | Android requires it for *any* BLE scan — this app never reads your location, and there is no code in it that does |
| Notifications | so the foreground service can say a scan is running |

The location one surprises people. It is an Android platform rule for Bluetooth
scanning, not something this app wants; denying it stops the adapter being found at all.

**First run:**

1. Plug a BLE ELM327 adapter into the OBD port and switch the ignition on.
2. Open the app and tap **START**. Three lights — scan, link, ready — show which step
   is which, so a failure names itself rather than collapsing into "not connected".
   If the adapter is not in the list by name, tap it anyway: the name list ranks
   candidates, it does not gate them.
3. Tap **CAPTURE**. It reads the VIN and stored codes, then maps the vehicle — 15–20
   minutes parked on a car it has not seen before, seconds on one it has.
4. Drive when it says to. **22 minutes is the floor** for a useful log; the bar turns
   green when there is enough.
5. **EXPORT SCRUBBED** gives you a zip that is safe to attach to a public issue.
   **EXPORT RAW** keeps the VIN and is for your own records.

**CONTROLS TEST** is the other half: you operate the air conditioning, lights, brakes
and steering to a prompt while it logs. Fields with no relationship to speed or
temperature can only be identified that way, and it works parked.

## Reporting something

Tap **REPORT** and share the zip. It is always available — including when nothing connected
and there is no capture to export — and it is always scrubbed, because it exists to be
attached to an issue. It holds the build tag, your Android version and device model, what
the adapter identified itself as, the protocol, and the adapter log. No VIN, no Bluetooth
address, no drive data.

If the problem is about a scan rather than a connection, a **scrubbed export** is better —
same log, plus the map and the drive.

Either way a report is much more useful with two things in it:

- **The build tag**, shown under the app's title on the main screen (e.g.
  `build 2026-08-28y · non-CAN progress bar`) and written into the top of every export.
- **A scrubbed export**, which carries `adapter-log.txt` — the only file that records what
  was *asked*, not merely what answered. Several defects here were only diagnosable from
  it, and one of them had to be transcribed from a photograph of the screen because the
  log was not yet being exported.

The vehicle helps too: make, model year, and whether it is CAN or one of the pre-2008
protocols. The app prints that on the identity card.

## Building

Android Studio, or:

```
./gradlew assembleDebug
```

Needs a JDK 17+ (Android Studio ships one at
`/Applications/Android Studio.app/Contents/jbr/Contents/Home` on macOS). Unit tests:

```
./gradlew testDebugUnitTest
```

Debug builds are debug-signed. Release builds are signed with a real key read from
`keystore.properties`, which is not in the repo; without it the release build comes out
**unsigned** rather than debug-signed, because a debug-signed release installs fine and then
breaks every future update.

## Licence

MIT for the source. Five bundled data files come from third parties and keep their own terms:

- `obdb_hints.json`, `obdb_supported.json` — adapted from [OBDb](https://github.com/OBDb),
  **CC BY-SA 4.0**. Locations only: which header, which service, which block, which DIDs
  answer — not the signal names or decode formulas. As adaptations they stay CC BY-SA 4.0, so
  redistributing them means crediting OBDb and keeping that licence.
- `dtc_generic.json` — [dtc-database](https://github.com/Wal33D/dtc-database), **MIT**.
- `wmi_to_make.json` — derived from [NHTSA vPIC](https://vpic.nhtsa.dot.gov), **public domain**.

Parts of the anchor decoding and the advisory drive check are ported from
[obd-gauge-cluster](https://github.com/cheeseprince/obd-gauge-cluster) (MIT, © 2026 Alan Young).

See [LICENSE](LICENSE), [ATTRIBUTION](app/src/main/assets/ATTRIBUTION.txt), and
**sources & licence** inside the app.

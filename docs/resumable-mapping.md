# Resumable mapping

Why a vehicle map is built ten minutes at a time instead of in one sitting, and
what that costs.

## The problem is how many identifiers a modern vehicle answers

cheeseprince raised this in [#8](https://github.com/radiohound/obd-discover/issues/8):
a GM Global B scan produced a drive log primed with **1,853 DID columns**. Our own
Silverado HD answers **1,936**. A row of a log is one request per column, so the
volume decides everything downstream.

| vehicle | identifiers | one row | 30 samples, which `correlate` needs |
| :--- | ---: | ---: | ---: |
| Silverado HD | 1,936 | 7.7 min | **3.8 h of driving** |
| GMC Sierra (#8) | 1,853 | 7.4 min | 3.7 h |
| Ford F-150 | 597 | 2.4 min | 1.2 h |
| BMW 535i | 572 | 1.7 min | 0.9 h |
| Subaru Forester | 246 | 1.0 min | 0.5 h |

His words for it: the drive would not be *slow*, it would be *void*. He is right,
and the figures above are worse than the ones he had — he computed 2.5 min/row
from a rate of 12 probes/s, and the first captures to carry a clock measured
**2.9–5.5**. His point is about three times stronger than he could show.

That same volume breaks the other half too. Finding 1,936 identifiers takes
25–75 minutes of blind sweeping, and an Ioniq 5 with every documented header live
is about **two hours**. Nobody sits through that, so in practice the map either
does not get built or gets abandoned partway and started over next time.

Two problems, one cause:

- **The log is too wide to sample usefully.** That is #8, and the answer is
  `PreDriveTriage` — merged in #4, ranking columns so a drive logs the movers.
- **The map takes too long to build in one sitting.** That is this document.

They are complementary. Triage decides what a drive should carry; resumable
mapping decides how the map gets built at all.

## The work was already in pieces

| unit | probes | BMW | Ioniq 5 |
| :--- | ---: | ---: | ---: |
| one block of sweep | 256 | 0.8 min | 1.5 min |
| one header of recon | 1,792 | 5.5 min | 10.3 min |
| recon's share of a run | — | 38% | 56% |

A sweep is a queue of one-minute jobs; recon is a queue of per-header jobs.
Neither needs a single sitting. What was missing was not a design — it was the
position in the queue, which was discarded the moment a run ended early.

## Starting from what is already known

A blind sweep asks 256 identifiers per block to find roughly 25. It ran that way
even on vehicles this project had already mapped.

| vehicle | known | confirm | blind sweep | + recon |
| :--- | ---: | ---: | ---: | ---: |
| BMW 535i | 572 | **1.7 min** | 13.2 min | 10.9 min |
| Silverado HD | 1,936 | **7.7 min** | 44.7 min | 28.4 min |
| Ford Ranger | 257 | **1.0 min** | 15.2 min | — |

Measured identifier lists ship with the app (15.5 KB for seven records) and are
asked by name before any sweeping.

**Prior knowledge sets the order of the work and never its bounds.** A record
says what to ask first; it never says what to leave out. Absence from a record is
weak evidence, and this project has already been caught by it — a BMW F10
answered on three blocks the community list did not contain. The sweep still runs
in full behind the confirmation.

## What resumption breaks if it is allowed to

The mechanism that makes resuming work is *skip what is done*, so a block swept
while the vehicle was unavailable would be written down as done-and-empty and
never revisited. **Resumption converts a transient failure into a permanent one,
using its own core feature to do it.**

This is not hypothetical. A BMW re-map lost nine consecutive blocks to a
refuelling stop mid-sweep, and reported `timeouts: 0` and `retries: 0` throughout
— the DME was answering the whole time, just with `conditionsNotCorrect` instead
of data, so no liveness check fired.

Three mechanisms answer it:

**An empty block is never done.** Ten of twelve captures contain no empty block
at all, because a block only enters the sweep when recon already answered there.
So re-queueing costs nothing on a healthy vehicle and repairs an interrupted one
by itself, with no threshold to tune. The escape hatch is repetition across
*separate* runs: seen empty twice, the vehicle has contradicted its own recon
twice and is believed. One run cannot decide it, because one run is exactly what
an outage looks like.

**An overlap block, re-swept each session.** About a minute. It insures against
gaps at a session boundary, and it is also the only cheap way to learn whether
two sessions are comparable at all — a multi-session map is inherently
multi-state. Same hits and the states match; different hits and they do not,
known in sixty seconds rather than discovered in a merged map weeks later. The
union is kept either way: an identifier that answered once is a fact about the
vehicle.

**A live warning at three consecutive empty blocks.** Three because a healthy
capture has never produced one. The value is entirely in the timing — nine blocks
is a ruined run found at a desk, three is a run somebody saves by reaching over
and turning the key.

## What each capture records

`swept` (all 256 offsets were asked — a stop partway leaves it false),
`empty_runs`, the `state` and timestamp the block was swept in, and
`recon_headers` for the headers recon walked to the end. Progress is merged
across every capture for a vehicle rather than taken from the newest: recency
decides which fact wins, never which facts exist.

## Sessions

Ten minutes of mapping, then the drive that was happening anyway. Both buttons
are budgeted; Re-map means *discard what is known and begin again*, not *sit here
for an hour*. A paused session is not an aborted one — nothing went wrong, so it
advances the map and then logs the drive.

## Decisions

Settled: sessions are time-boxed (D1); mapping and logging share a connection
(D2); prior knowledge never shortens the work (D9); measured identifier lists
ship with the app (D11). State is recorded per block and warned about rather than
required (D3); the overlap is one block (D4); an empty block is believed after
two separate runs (D5); progress lives in the capture file (D6); Re-map starts
over and CAPTURE continues (D7); recon resumes per header (D8).

Open, and all the same root — the model allows one state per block and a vehicle
sometimes has two:

- the overlap can merge identifiers from two states under one stamp
- "believed empty" is not state-aware, so two empties at *key on, engine off*
  retire a block that might answer while moving
- re-asking the refusal list in motion is untried. A BMW holds 500+ identifiers
  that answered `conditionsNotCorrect`, and no map this project holds was made
  anywhere but parked

## What this does not solve

The drive log stays as wide as the map, which is #8 and belongs to
`PreDriveTriage`. The run-time estimate is still imperfect; it now grades itself,
which is different from being right. And nothing here shortens a first session on
an unknown vehicle, because recon has to find the blocks before anything can be
resumed.

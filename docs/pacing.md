# Why the scan paces itself

**Read-only is not the same as no side effects.** Everything this app sends is a read, and
that guarantee is real — but a read is still a question, and asking thousands of them quickly
is something a vehicle can notice.

## What happened

On 2026-08-30 an Ioniq 5 stopped recognising either of its keys. The scan had just finished;
the first thing done afterwards was to press lock on the fob, and nothing happened. Repeated
attempts to start it followed, with that key and then with a spare, and it accepted neither —
displaying its prompt to press the start button with the key. So it was awake and looking for
a key it would not accept, rather than asleep, and both the button and the transponder paths
had failed on two separate keys. The 12 V battery measured a good voltage throughout.

It recovered on its own overnight — no jump start, no battery disconnect, no intervention of
any kind — which is what a timed protective latch looks like rather than damage. No trouble
code was read from it, though nothing capable of reading one was connected: a smart-key fault
is a body-domain code, and Mode 03 returns emissions-related codes only.

## What the captures show

**We cannot show the scan caused it.** One vehicle, one occurrence, nothing diagnosed. What
the captures do show is three sessions the same day, on the same car, in the same state — in
Drive with the brake held:

| session | modules getting traffic | our requests/s | refusals | refusals/s | outcome |
|---|---|---|---|---|---|
| 09:21 | `7E2` | 2.9 | 1,796 | 0.8 | fine |
| 14:05 | `7E2` | 5.1 | 884 | 1.5 | fine |
| 15:08 | `744`, `7E2`, `7E3` | **10.3** | **5,740** | **9.6** | keys failed after scan completed |

The second and third sessions probed the same five modules, so the wider module set was not
what was new — it had already run once without incident. What was new is everything to the
right of it: three modules answering *no such identifier* over a thousand times each, without
ever returning data, at six and a half times the previous peak.

## Which number matters is not known

There are at least two explanations and this evidence cannot separate them.

**Requests arriving.** A module that reacts defensively would count what it receives. But
per module the increase was modest — about 2.9 requests/s each in the last session against
1.4/s in the one before — and 2.9/s is a low figure to trip anything. Unless a module counts
all diagnostic traffic it can see rather than only its own, in which case the aggregate is
the number and the jump is much larger.

**Refusals.** A sustained stream of *no such identifier* from one tester is what enumeration
looks like from the bus's side, and it is the thing that most distinguishes the last session.
But the modules that refused are not necessarily the module that latched.

Our own request rate is the least trustworthy of the three, because it is an output rather
than a setting. A reply — data or a refusal — comes back in tens of milliseconds, while
silence costs the adapter's full wait before it gives up. The last session ran fast **because**
the car was answering it: 93% of its probes drew a reply, against 28% and 29% in the two
before. And a refusal is the cheapest reply there is, three bytes on one line.

So the app applies both limits. Each covers the case the other misses, and both are cheap.

## What the app does now

- **A ceiling of 5 requests/s.** Not from the incident — from the drive log, which polls
  known-good identifiers at 5.0–5.6/s for up to 34 minutes at a time and has never caused a
  problem on any vehicle. That makes 5/s the highest rate this project has sustained evidence
  is harmless. It also sits just under the BLE link's own floor: every request is a write
  plus a notification across a connection interval of tens of milliseconds, which is why
  probes land at 97–346 ms whatever they ask. Measured across every session on record it
  costs 1.19× — it bites only where replies got unusually cheap, which is the enumeration
  case.

  It also makes the rate a property of the app rather than of the adapter. Without it there
  is no rate policy here at all, only whatever the dongle imposes: these figures are from a
  Vgate iCar Pro BLE 4.0, and an adapter negotiating a shorter connection interval could run
  several times faster on identical code. That would put its owner well past any rate this
  project has evidence for, and the clean record on the vehicles above is partly an accident
  of the hardware being slow. The ceiling is the same scan at the same rate whoever's
  adapter it is.
- **A back-off on consecutive refusals**, decaying toward 5/s at a physical address and 3/s
  on the functional broadcast, which every module on the bus receives. A sweep that is
  finding identifiers resets the count every few offsets and is never slowed below the
  ceiling; a barren one decays to the limit within a single block's worth of silence.

Every capture records the rate achieved, the split between broadcast and physical probes, and
the longest unbroken run of refusals, so these limits can be revised from evidence instead of
adjusted by feel.

## What this evidence is not

- **Not a controlled comparison.** Those three sessions ran three different builds of this
  app, 867 lines apart, including a fix to multi-frame reply parsing and a change to how much
  of each module recon covers. How *our* end behaved differed for reasons that were ours.
- **Not a causal finding.** One vehicle, one occurrence, no diagnosis, and no second case.
- **Not informed by a drive log on that car.** The drive-log evidence that 5/s is harmless
  comes from a BMW, a Ford and a Subaru. Discovery on the Ioniq never finished, so it never
  reached a drive.

What is not in doubt is what the vehicle emitted, because it is recorded per module: 5,740
refusals in ten minutes, from three modules that returned no data at all.

## If it happens to you

**If a vehicle stops recognising its key after a scan: disconnect nothing, and give it time.**
Disconnecting the 12 V battery destroys the evidence and on many cars adds a sensor
recalibration you did not need. Ours came back by morning.

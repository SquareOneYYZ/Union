# 21 — MVP1 gaps, three fixes each

> **Note, 2026-09-08.** Written against revision 1 of `20`. The recommendations here are folded into `20` revision 2, which also carries the 2026-09-07 detection facts (production window now 4 × 250 m; the loss is map data; Beachline fragments and must be merged). Where a number here conflicts with `20` r2, `20` r2 wins. Gap 1 is dissolved by the table design in `22`; Gap 3's size budget no longer applies under it. Every mention here of a holiday calendar as "MVP1.1" is superseded: on 2026-09-08 the owner put peak / off-peak / holiday schedules for all facilities into MVP1 (`20` r2 §3.8).

**Date:** 2026-09-07 · **Companion to:** `20-mvp1-scope.md` · **Status:** options, nothing
decided, no code written.

Eleven gaps were found reviewing `20` against the source on 2026-09-07. Each gets three fixes
with cost and trade-off, then a recommendation. The recommendation is one reviewer's; the
choice is the user's. Where a fix touches the detection state machine it says so — that code
belongs to the detection track and changes there need its own review.

Code anchors verified this session: `TollRouteState.fromDevice` / `toDevice` (`:35-69`),
`TollEventHandler.onPosition` (`riq-toll-diagnostics` working tree, `:180-333`),
`TollRouteProcessor.updateState` (`:21-73`), `ProcessingHandler` chain (`:112-151`), index
`position_deviceid_fixtime` (`schema/changelog-4.7.xml:21`), `tc_events.attributes` is
`VARCHAR(4000)` (`schema/changelog-4.0-clean.xml:171`).

---

## A hard constraint that shapes several fixes

**`tc_events.attributes` is `VARCHAR(4000)`.** Everything the exit event already carries
(`tollDistance`, `tollEventSchema`, `tollName`, `tollRef`) plus every `tollCost*` attribute plus
the `tollCostInputs` JSON must fit in 4,000 characters, or the insert fails and the event is
lost. Budget: keep `tollCostInputs` under ~2,500 characters. Consequences:

- Passages are stored as **cluster ids and offsets**, not gantry ids with timestamps
  (`"p":[[12,0],[13,241],[15,610]]` — cluster id, seconds after entry).
- Zone kilometres are stored to one decimal.
- The card is referenced by `valid_from` only; `source_url` lives in the card, not the event.
- An estimator unit test asserts the serialised attribute map is under 3,800 characters for a
  worst-case traversal (longest ISTHA trail in the corpus).

---

## Gap 1 — State loss mid-traversal leaves no entry mark

**What breaks.** On a Redis flip (`exists` succeeds, `get` returns null, `readValue` throws,
state nulled — ~161 incidents/day, `TOLL-RESUME.md` §0) the handler rebuilds state with
`fromDevice`, which restores `tollName`, `tollRef`, `tollStartDistance`, `tollExitDist`,
`tollrouteTime` and `id` — **not** the window and **not** the marks. The same is true for every
traversal in progress at deploy time. `20` §2.6 refuses those exits with `NO_ENTRY_MARK`.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Storage fallback at exit.** `tollrouteTime` is persisted on the device row and, after 1c, equals the entry position's `fixTime`. Query `deviceid = ? AND fixtime = ?` (index `position_deviceid_fixtime`), widening to ±60 s if empty. | One indexed read, only on the fallback path. | Still a synchronous read on the processing thread, but rare by construction. |
| **B** | **Persist the entry mark on the device row.** `toDevice` writes `tollEntryLat`, `tollEntryLon`, `tollEntryPositionId` into device attributes next to `tollExitDist`; `fromDevice` restores them into `entryMark`; the exit-time cleanup at `toDevice:58-64` removes them with `tollRef`/`tollName`. | Three keys in device attributes for in-traversal devices; the row is already rewritten on every state change (`onPosition:320-328`). | The keys are visible on the device object in the API, as `tollName` is today. |
| **C** | **Fix the transport (PR 2b).** One store or reconciled stores; the flips stop and the marks survive. | A separate PR with its own design decision (`TOLL-RESUME.md` §5.3). | Not an MVP1 dependency; removes the cause rather than the symptom. |

**Recommend B, with A as the safety net** for traversals that started before B deployed. C on
its own track. `NO_ENTRY_MARK` remains as the reason code only when both fail.

---

## Gap 2 — Cross-operator traversals are one event

**What breaks.** Continuous tolled road merges into one traversal (`TollFacilitySeparationTest`:
under ~2.5 km untolled does not separate) and `tollRef` is set once at `stateStartToll` and
only filled if null afterwards (`updateState:44-50`), so it names the *first* facility. 407 ETR
runs into the MTO 407 East; SR 528 Beachline is split between CFX and Florida's Turnpike; the
Illinois Tollway runs into the Skyway and then the Indiana Toll Road. `20` assumed one facility
per exit.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Attribute per passage, not per traversal.** Every gantry cluster carries its facility id; flat passages are priced against the cluster's facility. The 407 corridor file carries an operator per zone (Z1–Z12 ETR, then MTO zones on the MTO per-km rate). Per-distance facilities use **trail length inside the facility polygon**, not `tollDistance`. `tollCostFacility` = the facility with the largest subtotal; `tollCostInputs.breakdown` lists each. | Registry gains a polygon (or bbox) per facility; corridor file gains an operator column. | Currency stays single per event only because no adjacent cross-border pair exists in the corpus; assert it, do not assume it. |
| **B** | **Split the traversal at operator boundaries** in the state machine: exit + enter when the enrichment's operator changes. | Touches detection; needs operator per lookup, which the current Overpass query does not return (`operator` on 0.26 % of tolled ways) — needs the route-relation lookup first. | Correct in principle (ETR and MTO bill separately, so flat fees per operator are right), but not feasible in MVP1. |
| **C** | **Price under the entry facility and flag.** When the exit coordinates fall in a different facility's polygon, set `tollCostMixedOperators = true`. | Trivial. | Wrong for the second operator's share; but entry/exit coordinates and passages are persisted, so a later A can re-estimate. |

**Recommend A**, C as the behaviour when a facility has no polygon yet.

---

## Gap 3 — The trail read blocks the processing thread

**What breaks.** `20` §2.4 reads the positions between entry and exit synchronously inside
`TollEventHandler.onPosition`. The index exists, but a slow read stalls every device behind it
in the chain. This pipeline has already been hurt by one hot-path cost (the log flood), and
the storage layer has had its own slow periods (`rides-iq-be-db-partition-investigation-2026-07-12.md`).

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Accumulate passages in state.** While `tollStartDistance > 0`, on every position (readings of `null` included) keep a `lastPosition` mark and test the segment `lastPosition → current` against a spatial grid of the facility's gantry clusters, but only when the position is inside a flat facility's bbox. Append `[clusterId, secondsSinceEntry]` to a bounded list (cap 200). Distance models need only the entry and exit marks, so they never need a trail. | Per-position work for in-traversal devices only (12.8 % of the fleet is moving at peak, far fewer inside a flat facility). O(1) grid lookup. | Redis blob grows by the passage list. A flip loses it — Gap 1's fallback then applies, and the trail read (B) becomes the rare-path fallback. |
| **B** | **Bounded synchronous read.** `deviceid = ? AND fixtime BETWEEN ? AND ? ORDER BY fixtime LIMIT 3000`, JDBC query timeout ~2 s. On timeout: `NO_TRAIL`, and still emit a per-distance estimate so the event carries a number. | Least code. | Risk is bounded, not removed; the timeout is a guess until measured. |
| **C** | **Price asynchronously after persistence.** Emit the exit without a cost, then a background task reads the trail and updates the event row's attributes. | An `UPDATE` on `tc_events` after the insert; the event id must be found again (device + type + eventTime). | Notification payloads never carry the cost; consumers must tolerate a late attribute; two code paths. |

**Recommend A, with B as the fallback** when the state has no passages. Not C in MVP1.

---

## Gap 4 — Nothing consumes the number

**What breaks.** The IoT API (`IoTride-API`, `EventsEntity.attributes` as a string,
`MasterService` parses only the keys it knows, e.g. `extractTotalDistance`) lists both toll
event types in `event-types.yml` but would not expose `tollCost*`. The frontend was not
checked. Traccar's own `/api/events` and `/api/reports/events` already return attributes as-is,
because `Event extends Message extends ExtendedModel`.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **IoT API ticket:** extract `tollCostEstimate`, `tollCostCurrency`, `tollCostTier`, `tollCostReason`, `tollCostFacility` next to `extractTotalDistance`; add them to the events DTO and swagger. | Small, in the other repo. | Consumers see a null estimate with a reason; they have to render that honestly. |
| **B** | **Frontend ticket:** render the five fields on the events list and the toll event detail; sum by currency, never across. Traccar's API needs nothing. | Small, FE track (keep separate from BE per the audit rule). | — |
| **C** | **A summary report:** `/api/reports/tollcost?deviceId&from&to` grouping by facility and currency from `tc_events.attributes` via `JSON_EXTRACT`. | A new report provider; the column is a string, so JSON functions run unindexed — fine at ~300 exits/day. | A second consumer path to keep in step with the attribute names. |

**Recommend A + B as the definition of shipped.** C when a dashboard total is asked for.

---

## Gap 5 — No validation before the invoice

**What breaks.** The itemised 407 statement is unscheduled (top unscheduled item across three
reviews). Without any check, "best estimate" has no measured error at all.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **ETR public trip calculator spot-check.** Take 20 schema-2 407 exits, map entry/exit to the nearest interchanges, enter the local entry time on ETR's calculator, compare. Record in `docs/toll-pricing/validation/407-spotcheck-<date>.md`. | An afternoon, no engineering. | Validates rate math and band logic against ETR's own model — not our detection of where the trip started and ended. |
| **B** | **Third-party cross-check.** Run the same trips through a toll-pricing API (TollGuru or similar) from a script in `scripts/toll_research/`. | An API key and a script. | The 407 is a known vendor gap, so coverage may be partial; useful mainly for the US facilities. |
| **C** | **A driver's own statement.** Any employee who drives the 407 in a tracked vehicle has one; match a month of trips by date and time. | Zero engineering; requires asking. | The only end-to-end check (detection + pricing) available before the fleet invoice. |

**Recommend A this week and C asked for now.** B when US facilities go live.

---

## Gap 6 — The backlog stays unpriced

**What breaks.** Schema-2 events since 2026-08-27 carry both edges (enter `positionId` = run
start, exit `positionId` = last confirmation, `tollDistance`), and positions live six months
before `archive_cold_storage.py` deletes them. `20` §6 excludes re-estimation, so the first
dashboard starts empty.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Batch job using the same estimator.** Pair each schema-2 exit with the latest preceding enter per device, load both positions (and the trail for flat facilities), run `TollPriceEstimator`, write `tollCost*` into the event's attributes with `JSON_SET`. Exposed as a CLI main or an admin endpoint (precedent: riq-252). | 1–2 days once the estimator exists. | Must be the **same Java code**, not a Python port, or calc versions stop meaning anything. |
| **B** | **Price on read.** The report computes estimates for old events lacking attributes. | The estimator becomes callable from the API layer. | Two code paths; slower reports; history changes silently when rules change. |
| **C** | **Declare a start date.** Price from deploy forward; the dashboard says "priced since <date>". | Zero. | Loses ~2 weeks × ~300 exits/day of history at launch. |

**Recommend C for launch, A as the first follow-up.** Not B.

---

## Gap 7 — The 500 m gate shortens every traversal

**What breaks.** The entry mark is the first *confirming* lookup and lookups are ≥500 m apart,
so entry is 0–500 m late; the exit mark is the last confirming lookup, 0–500 m early. Mean
loss ~500 m per traversal, ~4 % low on the median 13 km 407 trip. Missing from `20` §4.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Snap to interchanges.** For distance-zoned facilities, project entry and exit onto the corridor, then snap entry back to the previous interchange in the direction of travel and exit forward to the next. This is how ETR bills (`19` A8: distance is a function of the entry–exit pair). | A rule in the projector; the corridor file already has interchange km. | Over-corrects when the vehicle genuinely entered mid-interchange (it cannot; entries are at interchanges). |
| **B** | **Midpoint marks.** Keep a `lastFalseBeforeRun` mark; entry = midpoint(last off-toll lookup, run start); symmetric for exit. | One more mark in state; touches state, not the window. | Bias → ~0 with ±250 m noise; still not how ETR bills. |
| **C** | **Constant correction.** Add 250 m at each end for distance models, recorded as `gateCorrectionM` in inputs. | Trivial. | Crude; must be removed (calc version bump) when A or B lands. |

**Recommend A for the 407**, C as an interim on `per_distance` facilities.

---

## Gap 8 — Per-direction gantry pairs double count

**What breaks.** Carriageway gantries are separate OSM nodes 20–40 m apart (`19` A4: 204
gantries collapse to 46 clusters on the 407). A 60 m segment threshold counts both.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Cluster at export time.** Merge nodes within 150 m into one plaza with a centroid and member ids; one passage per cluster per pass, with a 120 s debounce on the same cluster. | In the gantry export script; deterministic; also makes the `VARCHAR(4000)` budget easier (cluster ids are small). | Two genuinely separate plazas within 150 m would merge — check the ISTHA set, which has 176 `ref`s to compare against. |
| **B** | **Direction filter.** Compare segment bearing with the gantry's `direction` tag or its carriageway's `oneway` bearing from `out geom`; count only within ±45°. | Needs way geometry per gantry; `direction` tag coverage unverified. | Most precise; most data work. |
| **C** | **Nearest-only.** When several gantries fall within threshold of one segment, count the nearest. | Trivial. | Fails when the pair straddles two consecutive segments. |

**Recommend A, with C as a second guard.**

---

## Gap 9 — Exits fire late

**What breaks.** An exit needs `minimalDuration` (6) consecutive off-toll lookups ≥500 m apart,
i.e. ≥3 km of untolled driving. A vehicle that parks just after leaving prices on its next trip.
The event's `eventTime` is backdated correctly (1c); only emission lags.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Accept and make it measurable.** Dashboards query by `eventTime`; add `emittedAt` to `tollCostInputs` so lag has a number. | None. | Dashboards lag hours to days for the last trip of a day. |
| **B** | **Close on stop.** A periodic sweep over states with `tollStartDistance > 0` whose `lastTrue` is older than N minutes and whose device is offline or ignition-off closes the traversal at `lastTrue`. | A scheduled task in the handler; **detection-track change**. | A vehicle stopped *on* a toll facility (rest area, breakdown) would be closed wrongly and re-opened as a second traversal with a second set of flat fees. |
| **C** | **Asymmetric window.** Exit needs fewer consecutive off-toll lookups than entry (e.g. 3 vs 6). | A config split; **detection-track change**; interacts with query 3b's outcome. | Faster exits, but the merge window shrinks (~2.5 km → ~1.5 km) and fragmentation — Gap 4 in `20` — rises. |

**Recommend A for MVP1.** B or C only through the detection track, after query 3b.

---

## Gap 10 — Data files have no deployment path, and no snapshot pin

**What breaks.** `20` §2.3 loads cards from a directory named by `toll.pricing.path`; the hosts
do not have it, a reprice needs a restart, and the corridor and gantry files derive from an
OSM snapshot (`roadinfo` was 9 days stale when checked, and does not replicate) with nothing
recording which one.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Ship inside the jar.** Cards, registry, corridors and gantry sets as classpath resources under `toll-pricing/`; `toll.pricing.path` becomes an optional override directory that wins when present. | No host step; the existing jar deploy carries the data. | A reprice is a build. Restart to load. |
| **B** | **Host directory.** `/opt/traccar/toll-pricing/`, deployed the way `templates/` is; boot line echoes every loaded card's facility, `valid_from` and `osm_base`. | A deploy step; a card update without a build. | Still a restart; one more thing `setup.sh` must preserve across upgrades. |
| **C** | **Hot reload.** `POST /api/admin/toll-pricing/reload` (precedent: riq-252 admin endpoints) swaps an immutable registry reference atomically. | A small admin resource. | Needed before the first reprice date (2027-01-01 for ETR/ISTHA), not before. |

For all three: every generated file carries `osm_base`, `generated_at` and the generator script
in a header block; the boot line prints them; `tollCostInputs` records the corridor/gantry file
version it used.

**Recommend A now, C before 2027-01-01.**

---

## Gap 11 — No effort sizes

**What breaks.** `20` §5 orders the work but does not size it, so it cannot be scheduled or
challenged.

| | Fix | Cost | Trade-off |
|---|---|---|---|
| **A** | **Write the rough sizes into `20` with their assumptions** so they can be argued: WP1 ½ day; WP2 2–3 days; WP3 1 day; WP4 2–3 days; each card ½ day; each gantry facility 1–2 days once its card exists; WP6 ½ day. About a week for the 407 slice. | None. | These are one reviewer's guesses against code read once. |
| **B** | **A one-day spike first:** WP1 plus a 407-only estimator against one replayed track (`tools/tollRouteSample1.py`, local server recipe in `TOLL-RESUME.md` §0). Re-size everything from what the spike hit. | One day. | Delays the "real" start by a day; usually pays for itself. |
| **C** | **Timebox MVP1 to the 407 slice** (WP0–3) and run facilities as a rolling data backlog, one small ticket each. | None. | "All North American facilities" becomes a sequence, not a release — which is what the registry design already implies. |

**Recommend B, then C.**

---

## What changes in `20-mvp1-scope.md` if the recommendations are taken

| `20` section | change |
|---|---|
| §2.2 state | add `entryMark` persistence via device attributes (Gap 1 B); add `lastPosition` mark and a bounded passage list (Gap 3 A) |
| §2.3 registry | add a polygon per facility (Gap 2 A); registry and cards ship in the jar with an override directory (Gap 10 A) |
| §2.4 models | per-passage attribution by cluster facility; corridor file carries operator per zone; `per_distance` uses trail-in-polygon (Gap 2 A); 407 entry/exit snap to interchanges (Gap 7 A); gantry clusters, not nodes (Gap 8 A) |
| §2.6 attributes | the 4,000-character budget and the compact passage encoding; `emittedAt` and file versions in inputs (Gaps 3, 9, 10) |
| §3 data | every generated file records `osm_base` (Gap 10) |
| §4 errors | add the gate under-distance (Gap 7) and the late-exit lag (Gap 9) |
| §5 work packages | WP0 becomes the spike (Gap 11 B); add an IoT API ticket and an FE ticket as the definition of shipped (Gap 4); add the spot-check as WP7's interim (Gap 5 A) |
| §6 out of scope | backlog pricing moves from "out" to "first follow-up" (Gap 6 A) |

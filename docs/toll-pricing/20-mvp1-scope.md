# 20 — MVP1 plan, revision 2: a best-estimate toll cost per traversal

**Date:** 2026-09-08 · **Supersedes:** revision 1 (2026-09-07) in place · **Status:** plan, no
code written · **Branch for the work:** `riq-toll-pricing-mvp1`, cut from `riq-toll-diagnostics`
HEAD `be852ec48` — its `src/` is identical to the deployed `riq-toll-log-volume` and it carries
the replay tools; do **not** carry the uncommitted working-tree changes (diagnostic counters,
`debug.xml` with a live Valkey password).

Companions: `21-mvp1-gaps-and-fixes.md` (options per gap; recommendations folded in here),
`22-estimate-storage-design.md` (the storage recommendation; D1 still pending). Earlier layers
`00`–`19` on `riq-toll-pricing-research` are unchanged. Detection facts come from
`TOLL-RESUME.md` §0 (2026-09-07) and `scripts/toll_research/toll_event_trend.sql` §3b–§13.

---

## 0. Why this revision

1. **Production config changed on 2026-09-07.** `event.tollRoute.minimalDuration` 6 → 4 and
   `tollRoute.minimalDistance` 500 → 250. Every detection number in revision 1 describes the
   old state. 2026-09-07 starts the fourth era for every trend query; **2026-09-14 is the
   re-measure**, and no recall figure should be quoted before it.
2. **The missing third is now explained, and most of it is map data, not code.** An offline
   replay of one real day through the window semantics reproduces production (320 simulated
   vs 312 actual enters), so nothing is lost inside the pipeline. What is lost: the old 3 km
   confirm floor (fixed by the config), OSM corridor tag gaps on the Beachline, express-lane
   adjacency, and an inflated pre-gate baseline. Fair target: ~420–440 enters/day, not 458.
3. **D1 was reopened.** The estimate no longer has to live on the exit event; `22` recommends
   a dedicated table with a headline on the event. This revision is written for that design
   and marks it pending wherever it matters.

---

## 1. Decisions

| | State | Effect |
|---|---|---|
| **Accuracy** | Decided: best estimate, errors accepted, improved in place. | ±10 % in `15` is advisory. |
| **D1 where the estimate lives** | **Pending.** Recommended: `tc_toll_traversal` (open at enter, closed and priced at exit) plus four headline attributes on the exit event. See `22`. | Removes the 4,000-character cap, survives cold storage, gives columns to sum, holds the entry mark durably. |
| **D2 / D3** | Decided: `light`, `license_plate`; per-device override. | — |
| **D4 dynamic pricing** | Decided: excluded, reason recorded. | Also covers every express-lane facility in the corpus. |
| **D5 cold storage** | Decided: price at exit; persist inputs. | Table joins the never-delete snapshot list. |
| **D6 matching** | Decided earlier: Valhalla later. MVP1 does not wait. | — |
| **D7 build vs buy** | Build. | — |
| **Scope** | Decided: every North American all-electronic facility the fleet uses; adding one is data, never code. | Registry + cards + geometry. |
| **Variable rates** (2026-09-08) | Decided: **peak / off-peak / holiday schedules are in MVP1 for every facility**, not only the 407. | Every card carries a schedule; every facility names a holiday list; the band rule is per operator (§3.8). Congestion-dynamic pricing stays excluded under D4. |

Carried constraints: **never invent a rate** (`source_url`, `source_type`, `valid_from` on every
row) and **absent means unknown, never zero**.

---

## 2. What detection gives pricing now

### 2.1 Facts as of 2026-09-08

| fact | value | consequence for pricing |
|---|---|---|
| Window | 4 consecutive real readings; lookups ≥ 250 m apart | confirm floor under 1 km both for enter and exit; entry mark 0–250 m late, exit 0–250 m early |
| Pipeline fidelity | replay 320 vs 312 enters on Sep 3 | no state-loss term to design around for counts; PR 2b is a correctness fix, not a prerequisite |
| Replay tools | `replay_window.py`, `readings_by_facility.py` over `data prod/export_sep3.tsv` (untracked, device coordinates, keep out of git) | the same export can feed an **offline pricing dry-run** before anything ships (§6 WP4) |
| Beachline (SR 528) | mainline has 177 ways `toll=yes` and 59 `toll=no` interleaved | under the new window a `toll=no` gap fires an **exit after ~1 km**, so one drive becomes several enter/exit pairs — **pricing must merge fragments** (§3.6) |
| Florida refs | every ref renamed at the Aug 23 extract (`FL 528 Toll` → `SR 528 Toll`, 417/408/429 likewise) | `tollRef` is not a join key even for the same road; **match by geometry**, keep both spellings as hints |
| Express lanes | `US 36 EXPR`, I-70 Mountain Express, I-25 Express, CO 470: one-lane `toll=yes` beside free lanes; pre-gate enters there were mostly adjacency | all are dynamically priced → already refused by D4; registry also carries `lane_resolvable=false` for the detection track |
| Bare refs `93`, `1A` | junction exit numbers won by the first-wins scan, on Parks Canada roads | non-billable; nothing to price |
| Stale readings | 37 devices deliver ~80 % of their on-toll readings out of order on the wire; dead for counts | any trail pricing reads must **order by fixtime**; passages accumulated from live positions only are sparse on those devices (§3.7) |
| Facility identity | the handover's own next step is "facility identity from route relations or curated corridor geometry, not the per-way `toll` tag" | the registry, polygons and corridor files in §3.4 are **shared infrastructure** with the detection data track, not pricing-only |

### 2.2 Detection health by facility (era 3 vs pre-gate; runs of true readings ≥ 6 on Sep 3)

| facility | enters vs pre-gate | true readings in confirmable runs | what it means for pricing |
|---|---:|---:|---|
| 407 ETR (refs combined) | 107 % | 97 % | clean; tier A once the corridor file exists |
| E-470 family | ~119 % | 97 % | clean; flat model; card missing |
| I-294 (Illinois Tollway) | ~103 % | 98 % (I-90) | clean; flat model; card missing |
| SR 417 family (CFX) | 85 % | 85 % | usable; flat model; CFX card exists |
| I-25 Express | 83 % | — | dynamic; refused |
| SR 528 Beachline (CFX / FTE) | 41 % | 65 % | fragmented; merge rule required; expected to recover under the new window |
| US 36 (+EXPR) | 39 % | 12 % | dynamic express lane; refused |
| CO 470 | 0 % | — | no `toll` tag on the mainline; dynamic anyway; refused |

Expect the Sep 14 numbers to move all of these; the ordering is what matters for §4.

---

## 3. Design

### 3.1 Where it runs

`TollEventHandler.onPosition`, after `TollRouteProcessor.updateState` returns. Two hooks:

- **enter emitted** → insert the open traversal row (§3.3) and keep its id in state.
- **exit emitted** → find the row, close it, run `TollPriceEstimator`, write the pricing
  columns, stamp the headline on the exit event, then `callback.eventDetected`.

No consumer of `deviceTollRouteExit` exists in `src/main` outside the processor, so extra
attributes are backward-compatible. `DatabaseHandler` is the last position handler and event
handlers run after it (`ProcessingHandler:112-151`), so every position of the traversal is a
row by the time the exit is detected. About 300 exits/day today; more after Sep 7.

### 3.2 State (`TollRouteState`)

- `PositionMark` gains `latitude`, `longitude` (rollback-safe: `ignoreUnknown`).
- `entryMark`, set in `stateStartToll` from `runStart` when backdated, else the current
  position; cleared when `tollStartDistance` resets. In-memory copy of what the open row holds.
- `traversalId` (Long) — the open row's id, so the exit path does not need a lookup.
- `lastPosition` mark and a bounded passage list `[clusterId, secondsSinceEntry]` for flat
  facilities (§3.7), plus a counter of positions the latest-position guard dropped during the
  traversal (§3.7 uses it).
- Window logic untouched. The detection track owns it.

### 3.3 The record (pending D1) — `tc_toll_traversal` + headline

As specified in `22` §3, modelled on `tc_device_geofence_segment`. Columns in brief: device,
facility id, `open`; enter and exit position id, time, lat/lon, odometer; `distance`
(odometer path); pricing: `corridordistance`, `direction`, `daytype`, `band`, `vehicleclass`,
`paymentmethod`, `amount` (`DECIMAL(10,2)` nullable, or `amountcents BIGINT` nullable if the
storage layer cannot round-trip a null decimal), `currency`, `tier`, `reason`, `calcversion`,
`cardvalidfrom`, `pricedat`, `mergedinto` (new in r2, §3.6), `inputs TEXT` (JSON: zone km,
passages, breakdown by facility, thresholds used, file versions, `osm_base`). Unique
`(deviceid, enterpositionid)`; indexes `(deviceid, entertime)`, `(deviceid, open)`,
`(facilityid, entertime)`. Cold storage: snapshot list, never deleted. ~110 k rows/year.

Headline on the exit event: `tollTraversalId`, `tollCostEstimate` (absent when unknown),
`tollCostCurrency`, `tollCostTier`. Under 120 characters.

If D1 goes the other way, `22` §1 lists what breaks and revision 1's §2.6 encoding applies.

### 3.4 Facility registry and geometry — shared with the detection data track

`toll-pricing/facilities.json`, shipped inside the jar with an optional override directory
(`toll.pricing.path`). One entry per facility:

```
id, display_name, operator, country, region, currency, timezone (IANA)
billable            true | false
rate_model_type     distance_zoned | flat | per_distance | dynamic
lane_resolvable     true | false          (false for express lanes beside free carriageways)
match               { polygon, ref_hints[] (all spellings), name_hints[] }
rate_card, gantries, corridor            file names or null
defaults            { vehicle_class, payment_method }
merge               { gap_seconds, gap_meters }   (§3.6; defaults 600 / 5000, per facility)
```

**Matching is geometry first, hints second.** Entry and exit coordinates must fall inside the
polygon; hints only break ties. Reasons: the name attribute is inherited across traversals
(`19` B15), the Florida refs changed spelling at the extract boundary, and the 407 ETR versus
MTO 407 East split is a longitude boundary on the same route number.

The polygons and the 407 corridor file are the same artefacts the handover names as
detection's next step. Build them once, under `scripts/toll_research/`, with `osm_base` and
`generated_at` in every file's header, and let both tracks consume them.

### 3.5 Rate models and tiers

| model | facilities | computation | needs |
|---|---|---|---|
| `distance_zoned` | 407 ETR (+ MTO 407 East zones on their own rate, same corridor file) | project entry and exit onto the corridor → km; **snap entry back to the previous interchange and exit forward to the next** (how ETR bills; also removes the gate's under-distance); zones spanned × direction × entry band | corridor polyline with cumulative km, 13 boundary km, operator per zone |
| `flat` | CFX, ISTHA, FTE gantries, MDX, E-470 / NWP, A-30, Skyway, NYS Thruway, bridges | passages × the **gantry cluster's** rate **at the time of that passage** (cluster = nodes within 150 m, one passage per cluster per pass, 120 s debounce); unmapped cluster → facility median plate rate for that band | gantry cluster set per facility from `data/collection-points.json`; CFX cluster → plaza map; the card's schedule (§3.8) |
| `per_distance` | Indiana Toll Road, FTE ticket system, MTO 407 East until zoned, generic fallback | **trail length inside the facility polygon** × per-km rate for the entry band; `tollDistance` only when no trail | one sourced number per facility per band |
| `dynamic` | CO HPTE, FL FDOT express, GA SRTA, US 36, I-70 | refused, reason `DYNAMIC_PRICING_UNSUPPORTED` | — |

Cross-operator traversals (407 ETR → MTO; SR 528 CFX ↔ FTE; ISTHA → Skyway → Indiana) are
priced **per passage / per polygon**, with `tollCostFacility` = the largest subtotal and a
breakdown in `inputs`.

Tiers: **A** curated geometry and exact rate; **B** facility median or per-distance; **C** no
amount, reason recorded (`NOT_BILLABLE`, `FACILITY_UNKNOWN`, `DYNAMIC_PRICING_UNSUPPORTED`,
`NO_RATE_CARD`, `MERGED`, `SUPERSEDED`).

### 3.6 Fragment merging — new in revision 2

Under a 4 × 250 m window, a run of `toll=no` ways inside a tolled corridor closes a traversal
after ~1 km and the next tolled way reopens one. The Beachline does this today; any corridor
with a tag gap will. Left alone, each fragment on a trip-fee facility would carry its own
$1 + $5.30, and Beachline totals would be split across rows.

Rule, applied at exit before pricing (`TollPriceEstimator.mergeIfFragment`):

1. Find the device's most recent **closed** traversal `P` with the same facility.
2. Merge when `entertime(T) − exittime(P) ≤ merge.gap_seconds` **and** both `P`'s exit point
   and `T`'s entry point lie **inside the facility polygon** (a real exit leaves it). Without a
   polygon, fall back to `gap_meters` on the straight-line distance between the two points.
3. Price `T` as the union: entry = `P`'s entry, band from `P`'s entry time, passages = union,
   corridor distance from `P`'s entry to `T`'s exit, one set of flat fees.
4. Set `P.amount = NULL, P.reason = MERGED, P.mergedinto = T.id`. `P`'s exit event keeps the
   headline it was given; the table is the record and the re-stamp job (§6) can correct it.
5. Record `merge.gap_seconds`, `gap_meters` and the decision in `inputs`. Count merges in the
   aggregate log line.

A genuine re-entry after leaving the corridor is two trips and is charged twice by the
operator, so the polygon test, not the time gap, is what protects against over-merging.

### 3.7 Passages and position order

- Passages are accumulated in state from the positions the handler sees, i.e. **after** the
  valid and latest-position guards, as the segment `lastPosition → current` against a spatial
  grid of the facility's clusters, only inside a flat facility's polygon.
- For the 37 stale-heavy devices that stream is sparse. When the traversal's dropped-stale
  counter is above zero at exit, do the bounded trail read (`deviceid`, `fixtime BETWEEN`,
  `ORDER BY fixtime`, `LIMIT 3000`, 2 s query timeout) and recompute passages from it. Only
  those devices pay the read.
- Any trail read anywhere in this feature orders by `fixtime`, never by id or `servertime`
  (`tc_positions` is indexed on `(deviceid, fixtime)` only).

### 3.8 Rate schedules and holidays — every facility, MVP1 (decided 2026-09-08)

Every card carries a **schedule**, and every model reads its rate through it:

```
schedule:
  day_types:   weekday | weekend | holiday          (operator-defined; "holiday" may map to weekend rates)
  bands:       [ { day_type, start_local, rate_set } ]   one band when the operator publishes a single rate
  band_rule:   entry | per_passage                  407: whole trip at entry time; per-gantry facilities:
                                                    the time of each passage (passages already carry seconds since entry)
  holiday_list: id of a holiday table, with source_url and valid year
```

Resolution: a UTC `fixTime` → the facility's IANA timezone → date → **holiday table lookup
first**, then Saturday/Sunday, then weekday → band by `start_local`. Port of `resolve_band`
from `scripts/toll_research/price_407_reference.py`, generalised to the day-type order above.

**Holiday tables are per operator, not per jurisdiction**, because each operator publishes
which days take its holiday rate (the 407 card already models `weekend_or_holiday` as one day
type; which days count is **not yet sourced**). Tables live beside the cards
(`toll-pricing/holidays/<operator>-<year>.json`), carry provenance like a card, and are
refreshed **annually before 1 January** in the same pass as reprice detection. A facility whose
operator publishes no holiday rule gets an empty list, never an assumed one.

Recorded on every row: `daytype`, `band`, the holiday table id and year used, so a wrong list
can be re-estimated from `inputs`.

### 3.9 Observability

One aggregate INFO line per N exits, emitted outside any device context (per-device INFO is
filtered by `DeviceLogContext`): counts by tier, by reason, merges, trail reads, and a
`facility=? ref=? name=?` line for `FACILITY_UNKNOWN`. Daily truth is the table:
`SUM(amount) GROUP BY facilityid, currency, tier`, never across currencies.

---

## 4. Data, in the order it is needed

| item | status | work |
|---|---|---|
| 407 corridor polyline + zone km + polygon | ❌ | `out geom` on relation `109574` from `roadinfo.iotrides.com`; km from the 86 exit-`ref` junction nodes; 13 boundary interchanges; operator per zone (ETR / MTO). **Shared with detection.** |
| Facility polygons for every registry entry | ❌ | bbox is acceptable for the first pass; Beachline needs a real polygon because the merge rule depends on it |
| 407 ETR light card | ✅ | — |
| CFX card | ✅ | — |
| Gantry clusters per flat facility | partial | export from `data/collection-points.json` (research branch), cluster at 150 m, record `osm_base` |
| CFX cluster → plaza map | ❌ | 58 plazas onto ~136 nodes; tier B until done |
| ISTHA, E-470/NWP, FTE, MDX, Skyway, NYS Thruway cards | ❌ | flat, published; one data PR each |
| A-30, Ambassador, Blue Water | ❌ | one number each |
| Indiana, FTE ticket, MTO 407 East per-km | ❌ | one number each |
| Non-billable rows (Parks Canada roads incl. refs `93`/`1A`, Lafontaine, free 400-series, Pikes Peak) | — | `billable=false` |
| **Schedules on every card** (2026-09-08) | 407 ✅ (12 bands); CFX card has no schedule yet — confirm from the operator whether any plaza varies by time, and record "single band" with provenance if not | the card builder captures the published schedule per operator; one band is a valid schedule |
| **Holiday tables per operator** (2026-09-08) | ❌ none sourced, including the 407's | one table per operator per year with `source_url`; refreshed before 1 January; empty when the operator publishes no holiday rule |

Facility order for cards, by detection health and event share: **407 ETR → E-470 → Illinois
Tollway → CFX 417 family → Beachline after the merge rule → Florida's Turnpike → the rest.**

---

## 5. Errors MVP1 knowingly carries

| # | error | direction | note | fix |
|---|---|---|---|---|
| 1 | Recall below the fair target until the map data is fixed | fleet totals **low** | re-measure 2026-09-14; the floor loss is gone, the Beachline and express-lane losses remain | detection data track (route relations / corridor geometry — the same files as §4) |
| 2 | Fragment merging mis-fires: over-merge a real re-entry, or under-merge across a long gap | either | polygon test bounds it; thresholds are per facility and recorded | tune from `inputs` after Sep 14 |
| 3 | Holiday tables wrong or incomplete for an operator | either | a missed holiday prices a cheap day as expensive; an invented one does the reverse | source each operator's list; empty list when none is published; annual refresh |
| 4 | Band error | either | 11 % median, 49 % p90 per step on the 407 | schedule fidelity per card; clock drift |
| 5 | Gate under-distance | slightly **low** | now 0–250 m per edge; removed on the 407 by interchange snapping | corridor snapping for other distance facilities |
| 6 | Tier B medians and per-distance fallbacks | either | facility-dependent | map clusters, source cards |
| 7 | Card staleness after 1 Jan / 1 Jul | **low** | flagged, still emitted | reprice detection via `build_rate_cards.py` |
| 8 | Wrong facility from an inherited name | wrong row | geometry-first matching bounds it | log mismatches |
| 9 | Passage misses on stale-heavy devices | **low** on flat facilities | trail read recovers most | measure per device |
| 10 | Late exits (parked right after leaving) | timing only | `eventTime` is right; `pricedat − exittime` is measurable | detection track |

---

## 6. Work packages

| WP | content | gate / test |
|---|---|---|
| **0** | One-day spike: §3.2 marks + a 407-only estimator against one replayed track (`tools/tollRouteSample1.py`, local recipe in `TOLL-RESUME.md` §0a). Re-size everything after. | runs locally |
| **1** | State changes (§3.2). | `TollRouteStateWindowTest`, `TollRouteReplayTest` extended: exit carries entry coordinates and time |
| **2** | `org.traccar.tollpricing`: registry loader, card models, estimator, merge rule, table + model + resource + report + cold-storage entry (pending D1), headline writer, `toll.pricing.path`. | Java and `price_407_reference.py` agree to the cent on the same inputs; NULL amount round-trips |
| **3** | 407 corridor + zone km + polygon; registry polygons for every facility in §2.2. Shared with the detection data track. | junction coordinates → known km |
| **4** | **Offline pricing dry-run** (new): drive `data prod/export_sep3.tsv` through `replay_window.py` at duration 4 to get simulated enter/exit pairs, feed them to the estimator (a test harness or a CLI main), report the tier / reason / facility distribution and merge counts for a real day. **Gate for any production deploy.** The export holds device coordinates: never commit it or its outputs. | distribution reviewed by a second person |
| **5** | Flat facilities: cluster export, passage accumulation, adaptive trail read, CFX plaza map, Beachline polygon. | synthetic trails at 5 s and 30 s cadence; per-direction pairs count once; out-of-order trail counts once |
| **6** | Cards, in the §4 order. Data PRs. | schema validation |
| **7** | Observability line + daily SQL; **Sep 14 checkpoint** re-run of `toll_event_trend.sql` §3b, §7, §12 with the era-4 branch (§11) before any recall number is quoted. | — |
| **8** | IoT API ticket (expose the five fields; new entity if D1 is the table) and frontend ticket (render, sum by currency). **Definition of shipped.** | separate BE / FE deliverables |
| **9** | Validation: hand-price 20 real 407 traversals on ETR's public trip calculator this week; ask now for one month of a driver's 407 statement; the invoice when it comes. Record under `docs/toll-pricing/validation/`. | — |

**First follow-up after launch:** backfill schema-2 events since 2026-08-27 with the same Java
estimator (plain inserts under the table design). It must obey the production query rules
learned on Sep 7: anchor on `(deviceid, fixtime)`, never filter on `servertime`, run detached
and watched. **Not** a Python port.

Standing rules: fresh reviewer before anything prod-touching ships (the Sep 7 config change
went without one — do not repeat that for code); every enforced rule names its function
(§3.4 → `FacilityRegistry.match`, §3.6 → `TollPriceEstimator.mergeIfFragment`, never-zero →
`TollPriceEstimator.emit`); nothing is a billing input until an invoice has been reconciled.

---

## 7. Out of MVP1

Vehicle-class detection; non-light classes. Congestion-dynamic pricing (rates set in real
time) — schedule-based peak/off-peak/holiday pricing is **in** scope for every facility. Route-relation lookup at
enrichment time (extra Overpass load; the polygon does the job offline). Re-estimation job and
backfill (first follow-up). Report or API surfaces beyond WP8. Dynamic pricing. Repricing
history (a new card gets a new `valid_from`; old rows keep theirs).

---

## 8. What changed from revision 1

- Prod config is 4 × 250 m since 2026-09-07; recall claims wait for Sep 14.
- The loss anatomy replaced the "missing 34 %" story: floor (fixed), map data, express lanes,
  inflated baseline. Per-facility health drives the card order.
- Fragment merging added (§3.6) because the new window fragments tag-gapped corridors.
- Matching is polygon-first; Florida refs proved unstable across extracts.
- Registry gains `lane_resolvable`, `merge`, polygons; the geometry files are declared shared
  with the detection data track.
- D1 reopened; the plan is written for the table + headline design in `22`, pending.
- Passage accumulation is adaptive: live positions in state, trail read only for stale-heavy
  devices; every trail read orders by `fixtime`.
- Offline pricing dry-run (WP4) added as the deploy gate, using the Sep 3 export.
- Interchange snapping on the 407, gantry clustering, jar-shipped data files, IoT API and FE
  tickets, spot-check validation and backfill query rules folded in from `21`.
- **Amended the same day:** variable rates (peak / off-peak / holiday) are in MVP1 for every
  facility (§1, §3.5, §3.8, §4, §5). Per-gantry facilities price each passage at its own
  time; holiday tables are per operator with provenance and an annual refresh.

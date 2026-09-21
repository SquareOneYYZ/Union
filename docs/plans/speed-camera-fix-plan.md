# Speed Camera Detection — Fix Plan

**Status:** Plan r2.3 (stage A in review) · **r2.3 date:** 2026-09-20 (r2.2: 2026-09-18, r2.1: 2026-09-16, r2: 2026-09-13, r1: 2026-09-09) · **Branch for the work:** new branch off `master` (`riq-speed-camera-fix`) · **Single source of truth:** this file. `claude-code-speed-camera-fix-prompt.md` is generated from it and never edited on its own.

Goal: a `speedCamera` event means **"this vehicle is likely exposed to a fine"**: it passed a known,
operating speed camera, in the direction that camera enforces, faster than the limit that applies
there plus the operator's tolerance. The rule is evaluated on **every valid position**, as a
segment from the previous fix to the current one, against a **curated camera dataset** that ships
with the code. It is proven offline against the full prod export before it ships.

> Ground rules carried over from earlier work: no change touches prod without a fresh reviewer who
> did not sit in the building session; every rule in this plan names the function that enforces it
> or is marked *procedural*; test fakes must be able to express failure states, not only
> presence/absence. `debug.xml` points Redis at the production Valkey host, so local runs of the new
> handlers must override `redis.host` or they will write `speed_camera:<deviceId>` state into prod.

---

## 0. Decisions (2026-09-13)

| # | Decision | Consequence |
|---|---|---|
| 0.1 | **Event meaning = fine exposure.** | A camera whose program cannot issue a speed ticket on that date does not emit. Ontario municipal ASE (ended 2025-11-14) and Alberta intersection devices (red-light only since 2025-04-01) produce zero events. Enforcing: `SpeedCameraEventHandler.shouldFire` (§3.4). |
| 0.2 | **Inactive programs still annotate.** | Position attributes `speedCameraId`, `speedCameraDistance`, `speedCameraBearingOk`, `speedCameraLimit`, `speedCameraProgramStatus` are written on every match and the skip is logged and counted; only the event is withheld. Enforcing: `SpeedCameraHandler.onPosition` always writes; `shouldFire` withholds. |
| 0.3 | **`programStatus` stays on the event payload** even though only active programs emit. | Reports can distinguish `active` from `unknown`. |
| 0.4 | **Unknown status = active.** A jurisdiction with no calendar entry emits. | A missing entry never silently blinds a region. Enforcing: `EnforcementCalendar.statusOn` returns `UNKNOWN`, and `shouldFire` treats only `INACTIVE` as blocking. Pinned by unit test T-14. |
| 0.5 | **The camera dataset is a curated, versioned file in the repo.** A camera change is a deploy. | No boot-time dependency on any Overpass server; the live pull is an input of the *build script*, never of the running server. The build runs **monthly in CI and opens a PR** carrying the diff report; the owner reviews and merges. §2.2.1. |
| 0.6 | **`deviceSpeed` (km/h) stays as a deprecated duplicate for one release**, dropped the release after. | External consumers (Push API, RCR OpenAPI, iot-api) are being checked by the user; nothing in this repo reads it (grepped 2026-09-13). |
| 0.7 | **Customer-facing wording is "known camera"**, never "all cameras". | Coverage is what the dataset and the official lists cover (§2.2.8). |
| 0.8 | **Dataset owner: Luke, in the role of dev lead** (2026-09-16). | Name in the file header, role in this plan. The CI PR is assigned to the owner. |
| 0.9 | **Québec mobile radar sites emit**, with `speedCameraKind = mobile_site` (2026-09-16). | Baseline is reported as **fixed and mobile separately**. Mobile-site events are **excluded from stage B acceptance counts** and **included in the counters** (`emittedMobileSite`). Enforcing: `shouldFire` does not test `kind`; the harness and §4.3 split on it. |
| 0.10 | **The Routes speed-zone event builds on `speedCamera`** (2026-09-16). It and its historical pull ship after stages A and D; **the historical pull excludes rows tagged `suspect` by stage D.** | Procedural; the pull's query must carry `JSON_EXTRACT(attributes,'$.suspect') IS NULL`. |
| 0.11 | **A 5 km/h buffer over the limit before a detection fires** (2026-09-18), from stage A on. | Declared key `event.speedCamera.buffer` (km/h, default 5), read once and converted to knots. Enforcing: `SpeedCameraEventHandler` compare (stage A), `shouldFire` (stage B). **Resolved 2026-09-20: 5 km/h is also stage B's calendar fallback tolerance**, so A and B agree wherever no program value is published; published or assumed per-program values still override. |

---

## 1. Current state

### 1.1 Pipeline (unchanged since r1; line refs verified 2026-09-13)

```
ProcessingHandler.java:122  SpeedLimitHandler        every valid position, ungated
                                                    -> attributes.speedLimit  (KNOTS)
ProcessingHandler.java:124  PositionInfoHandler      gated by tollRoute.minimalDistance
                                                    -> attributes.highway, .enforcement
ProcessingHandler.java:143  SpeedCameraEventHandler  event stage, reads the three attributes
                                                    -> SpeedCameraState -> tc_events
```

- `OverpassSpeedLimitProvider.java:42-43` asks `way[maxspeed](around:100,lat,lon)` and takes
  `elements[0]` (`:67-68`). `parseSpeed` (`:46-56`) converts to **knots**.
- `PositionInfoHandler.java:153-168` returns early for any position less than
  `tollRoute.minimalDistance` from the last successful lookup (500 m code default; **250 m in prod
  since 2026-09-07**). Only positions that pass get `highway` / `enforcement` (`:224-230`).
- `OverPassTollRouteProvider.java:48-51` builds `(way(around:10,..); node(around:100,..););out tags;`
  — the node clause has **no tag filter**. `processApiResponse` keeps the first `highway` value
  unless a later element is `speed_camera` (`:150-156`), same for `enforcement` (`:157-163`).
  Results are cached 24 h under a key rounded to 3 decimals (`:29`, `:283-292`).
- `SpeedCameraEventHandler.java:70-113`: "at a camera" if `highway` is in
  `event.speedCamera.highwayTypes` (code default `motorway_link`, prod `speed_camera`, `:77`) or
  `enforcement` in `event.speedCamera.enforcementTypes` (`maxspeed,speed`, `:84`); fires when
  `position.getSpeed() * 1.852 > speedLimit` (`:91`, `:113`).
- `SpeedCameraState.addDetection` (`SpeedCameraState.java:31-70`): window 1, 60 s lock keyed on the
  `highway` string (`:41-48`), writes `speedLimit` (knots) and `deviceSpeed` (km/h) (`:53-55`).
- State persisted with `RedisCache.set` (`SpeedCameraEventHandler.java:139-146`), no TTL.
- `Position.KEY_HIGHWAY` / `KEY_ENFORCEMENT` are read **only** by `SpeedCameraEventHandler`
  (grepped 2026-09-13); the toll path stamps them and nothing else consumes them.
- Frontend `EventReportPage.jsx:405-417` formats `speedLimit` and `speed` only for
  `deviceOverspeed`. `templates/full/speedCamera.vm` prints only device name and time.
- Prod Overpass (`data prod/traccar.xml`): `speedLimit.url` and `tollRoute.url` both point at
  `http://147.182.153.145/api/interpreter`, whose extract predates mid-2025. The audit index came
  from `http://roadinfo.iotrides.com/api/interpreter` (OSM base 2026-08-23). **Two servers, two
  map ages.**

### 1.2 Evidence base (pinned)

| Item | File (`data prod/`) | Pinned |
|---|---|---|
| **Primary export** | `updated camrea events.tsv` | **2026-09-09, 42,210 `speedCamera` events with positions, 2026-04-01 to 2026-09-10, all groups** |
| Per-event evidence, both verdicts | `speed_camera_group8_full_check.tsv` | 42,210 rows, 2026-09-09 |
| Accurate set with corroboration, replay, implied speed | `speed_camera_accurate_detections_replayed.tsv` | 4,477 rows, 2026-09-10 |
| Fixes ±30 s around each accurate event | `20_positions.tsv` | 30,330 rows, 2026-09-10 |
| Overspeed events near detections | `corroboration_overspeed.tsv` | 9,638 rows, 2026-09-10 |
| Camera index snapshot | `speed_cameras_2026-09-09.json` | 2,546 nodes, OSM base 2026-08-23 |
| Per-camera official verification | `speed_camera_camera_verification.tsv` | 357 cameras, 2026-09-09 |
| Ticket exposure workbook (per-event grades, program status) | `Speed Camera Ticket Exposure - Apr-Sep 2026.xlsx` | 2026-09-12 |
| Full day of moving fixes, all devices | `day-exports/out/positions_sep3.tsv`, `positions_sep8.tsv` | 1,039,054 and 1,051,782 rows, delivered 2026-09-13 |
| Daily event counts per group | `day-exports/out/daily_counts.tsv` | 2026-04-01 to 2026-09-11 (skip 5 repeated header lines and the `NULL` group) |
| Prod config | `traccar.xml` | received 2026-09-10 |
| Audit scripts, official lists, intermediates | `C:\Users\Filing Cabinet\Documents\RidesIQ\speed-camera-audit\` | outside git, README with provenance |

Older files (`routes_5months_camrea detction.tsv`, 39,598 rows; `speed_camera_5months_*`;
`speed_camera_sep3_verdicts.csv`) are superseded and kept only for history.

### 1.3 Regenerated figures (all from the pinned export unless stated)

| Figure | Value | Share | Source |
|---|---|---|---|
| Exported events | 42,210 | 100 % | export |
| Events in `tc_events`, Apr 1 to Sep 10 | 42,892 = **263/day** over 163 days | | `daily_counts.tsv` (682 lacked a position or fell outside the export's id bound) |
| Per day, Sep 1 to 6 / Sep 7 to 10 | **280 / 462** | | `daily_counts.tsv`; the 2026-09-07 gate change raised events 65 % while `deviceOverspeed` stayed at ~56.5 k/day |
| Survive the unit fix alone (D1+D2) | 11,638 | **27.6 %** | `verdict_unit_fix_only` |
| Full rule (camera within 100 m, on-axis, over camera/road limit) | 7,030 | 16.7 % | `verdict_full` |
| Camera on an official list or no list exists (tiers A+B) | 4,477 | 10.6 % | replayed file |
| **Fine exposure** (4,477 minus programs verified inactive on the date: Alberta ISC 684, Georgia out of hours 51, Indiana 13, NC/OH/WY 9, Saskatchewan outage 12) | **3,708** | **8.8 %** = **23/day** | workbook column "Camera operating on date", decision 0.4 applied |
| — fixed sites (stage B acceptance basis, decision 0.9) | **2,361** | 5.6 % = **14.5/day** | |
| — Québec mobile radar sites (`kind = mobile_site`, emitted, counted separately) | **1,347** | 3.2 % = **8.3/day** | |
| Strict ticket-likely | 44 | 0.1 % | `speed_camera_ticket_likely.tsv` |

Ontario alone: 8,592 exported, 3,151 after the unit fix, 1,832 after the full rule (tier D), **0 under fine exposure.**

### 1.4 Findings (D1–D8 from r1, re-based on the pinned export; D9–D13 added in r2)

| Finding | Where | Evidence |
|---|---|---|
| **D1 Unit mismatch.** km/h compared to knots; effective threshold 54 % of the posted limit. | `SpeedCameraEventHandler.java:91,113` vs `OverpassSpeedLimitProvider.java:46-56` | 23,981 of 42,210 under the applicable limit. |
| **D2 Dead null check.** `Double speedLimit = position.getDouble(..)` boxes a primitive `0.0` (`ExtendedModel.java:100-102`); the `== null` branch never runs. | `SpeedCameraEventHandler.java:90,110` | 1,261 fired with limit 0. |
| **D3 Wrong-road limit.** First `maxspeed` way within 100 m, not the road under the vehicle. | `OverpassSpeedLimitProvider.java:43,67-68` | Stored lower than road-under-vehicle in 10,151 of 37,709 comparable rows, higher in 2,102. |
| **D4 Loose zone.** Any node within 100 m sets `highway`; camera direction ignored; 3-decimal cache cell smears the tag. | `OverPassTollRouteProvider.java:50,150-163,283-292` | Sep 8: 30,299 positions tagged `traffic_signals`, 30,602 `crossing`, 32,600 `street_lamp`. 1,156 events at perpendicular heading. |
| **D5 Sampled, not continuous.** Tags exist only on gate-passing positions. | `PositionInfoHandler.java:153-168` | Event rate tracks lookup rate: 280/day → 462/day when the gate halved on 2026-09-07. Sep 3: 78 % of moving fixes `tollLookupSkipped`; Sep 8: 61 %. |
| **D6 Inconsistent payload.** `speedLimit` knots, `deviceSpeed` km/h; FE shows neither. | `SpeedCameraState.java:53-55`, `EventReportPage.jsx:405-417` | |
| **D7 Weak dedupe, unbounded state.** 60 s lock on the `highway` string; Redis key never expires. | `SpeedCameraState.java:41-48`, `SpeedCameraEventHandler.java:139-146` | 92 of 7,030 full-rule events are repeats within 5 min. |
| **D8 Undeclared config.** Keys read as raw strings, no `Keys.java` entry, code default `motorway_link`. | `SpeedCameraEventHandler.java:77,84` | Prod runs `speed_camera` (traccar.xml). |
| **D9 Point-in-radius on sampled fixes misses most passes.** Removing the gate fixes D5's cause, not the sampling. | design of r1 §2.2 | Sep 3 consecutive moving-fix gaps: median 12 s; **median 108 m, p75 309 m, p90 448 m; at ≥ 40 km/h median 272 m**. 51 % of gaps exceed 100 m. A 50 m radius (100 m of path) catches roughly a third of highway passes; 100 m misses a third. "Every accurate track passes within 100 m" is survivorship: those events fired *because* a fix landed inside. |
| **D10 Program status ignored.** Ontario municipal ASE ended 2025-11-14; Alberta intersection devices red-light only since 2025-04-01; the window is entirely after both. | r1 never asked where the fleet's cameras are | Ontario: 8,592 exported / 1,832 full-rule events at cameras that cannot ticket. The 2026-08-23 snapshot still holds **171 Ontario speed-camera nodes (71 with a limit)** of 1,852; prod's older extract holds them all. r1's V4 track is a Brampton camera that no longer exists. |
| **D11 Direction semantics.** OSM `direction` is where the camera *aims*, which equals or opposes traffic depending on front/rear plate capture; values include cardinals, ranges, `both`, `forward`/`backward` (relative to a way the node query does not fetch). r1 spec "deg, nullable" drops all non-numeric forms; unit tests never pin modular wrap. | r1 §2.2 | Snapshot: numeric 909, absent 839, `forward` 48, `both` 20, cardinal 20, `backward` 9, multi 5, other 2. Audit script already parsed these and used modular wrap (`full_check.py:40-41,72-89`), so audit numbers stand. The canonical OSM structure is a **`type=enforcement` relation** (`from`/`to`/`device` roles, `maxspeed` on the relation): the map server holds **827 in the NA box, 242 `enforcement=maxspeed`, 177 with a `maxspeed` tag, 125 in ON+QC**; r1's node-only query sees none. |
| **D12 Nearest-then-direction shadows the right camera.** Opposite-direction cameras are routinely two nodes either side of the road. | r1 §2.2 `nearest()` | Snapshot: **338 speed-camera nodes have another within 30 m, 470 within 50 m** (a quarter of sites). |
| **D13 Toll provider dead weight.** After stage B nothing reads `highway`/`enforcement`; the untagged `node(around:100)` clause and the stamping cost Overpass load and `tc_positions` bytes and keep polluting `highway`. | `OverPassTollRouteProvider.java:50,150-163`, `PositionInfoHandler.java:224-230` | See D4 counts. |

Also measured: `course == 0` with `speed > 0` on 9,444 of 1,039,054 Sep 3 moving fixes (0.9 %);
`maxspeed:conditional` on 50 of 1,852 camera nodes; margins over the limit are small (median
6 km/h at the closest fix; 1,950 of 4,477 accurate events within 5 km/h).

---

## 2. Target design

### 2.1 Principles

1. **Fine exposure** (decision 0.1). Zone, direction, limit, tolerance and program status are all
   part of the rule; a miss on any one withholds the event.
2. **Segments, not points** (D9). Each valid position is joined to the device's previous position
   into a segment; the camera is matched against the segment. Enforcing: `SegmentBuilder.build`,
   `SpeedCameraMatcher.match`.
3. **A curated dataset, not a query** (decision 0.5, D10–D12). Cameras, their enforced direction,
   their limit and their program are resolved **at build time** by a script with named inputs and
   provenance, and shipped as a versioned file. The running server never resolves a limit per
   position for camera events. D3 becomes a `deviceOverspeed`-only problem (stage C), and the
   ordering constraint between stages B and C disappears.
4. **A missing input is a non-reading, never zero** (D2): missing limit, missing dataset, missing
   previous position each skip with a counter.
5. **One unit** (D1): knots throughout, like `OverspeedEventHandler`.

### 2.2 Camera dataset (`src/main/resources/speedcamera/cameras.json`)

**2.2.1 Ownership and lifecycle (procedural, decisions 0.5 and 0.8).** Owner: **Luke, dev lead**
(GitHub assignee `lakha-riq`; confirmed by the user 2026-09-20). The name
goes in the file header (`"owner": "Luke"`); the role and the handle live here so the header does
not have to change when either does. Lifecycle:

1. A CI workflow (`.github/workflows/speed-camera-dataset.yml`, beside the existing `gradle.yml`
   and `release.yml`) runs `scripts/speed_camera/build_dataset.py` **monthly on a cron schedule**
   and on manual dispatch. It fetches Overpass nodes and relations and the official lists, builds
   `cameras.json`, and diffs it against the committed file.
2. If the diff is non-empty the workflow pushes a branch `speed-camera-dataset/<yyyy-mm>` and
   **opens a PR assigned to the owner** whose body is the diff report: cameras added, removed,
   moved > 30 m, retagged, and program-status changes, per program, with the OSM base date and
   each official list's fetch date. An empty diff closes the run without a PR.
3. The owner reviews the diff, decides which changes are real and which are mapping errors, and
   merges. The merge ships as a normal deploy: **a camera change is a deploy.**
4. Out of cycle: the owner can dispatch the workflow by hand after an official-list change, or
   override the bundled file on a running server with `event.speedCamera.dataset.file` (§2.5) for
   a hotfix, then land the same change through the PR path.

The live Overpass pull is an *input* of the build; the running server never calls Overpass for
cameras. If a fetch fails the workflow fails visibly and opens no PR; the committed file stays in
force (principle 4: no silent empty dataset).

**2.2.2 File header.**

```json
{ "schemaVersion": 1, "generated": "2026-09-13T00:00:00Z", "generator": "build_dataset.py <git sha> <args>",
  "osmBase": "2026-08-23", "osmServer": "http://roadinfo.iotrides.com/api/interpreter",
  "officialSources": [ {"program": "qc_transports_quebec", "file": "quebec_radars.geojson", "fetched": "2026-09-09", "url": "..."} , ... ],
  "owner": "Luke", "cameras": [ ... ] }
```

`CameraDataset.load` refuses a file whose `schemaVersion` it does not know and a file with zero
cameras; both are startup failures, not empty indexes (principle 4).

**2.2.3 Row schema.** One row per physical camera.

| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable: `osm:node:<id>`, or `official:<program>:<siteId>` when the camera exists only on an official list. Never reused for a moved camera (a move > 30 m is a new id; the old one is retired with `retiredOn`). |
| `lat`, `lon` | double | Camera position after precedence (official coordinates win when within 200 m of the OSM node; 1 km on motorway/trunk). |
| `program` | string | Calendar key (§2.3). `null` when no program is known → status `UNKNOWN`. |
| `kind` | enum | `fixed`, `mobile_site` (official site, camera present part-time), `intersection` (red-light + speed), `school_zone`, `work_zone`, `red_light_only` (never emits; kept so the annotation can say why). |
| `direction` | double or null | Degrees, **traffic** direction enforced. |
| `directionMode` | enum | `one_way`, `both`, `none`. |
| `directionSource` | enum | `official`, `relation`, `node`, `none` — precedence in that order. |
| `limitKnots` | double or null | Applicable limit. |
| `limitSource` | enum | `official`, `camera_tag`, `relation`, `way` — precedence in that order. (No official list checked so far publishes limits, so `official` will be rare; the slot exists so a future list can fill it.) |
| `limitConditional` | bool | A `maxspeed:conditional` exists on the node/way; `limitKnots` carries the **base** value. |
| `wayId`, `wayHighway`, `wayGeometry` | long, string, [[lat,lon],…] | The snapped road (2.2.5). Geometry is the way's polyline within 150 m of the camera; used by the on-way check at match time. |
| `provenance` | object | `osmNodeId`, `osmRelationId`, `osmBase`, `officialProgram`, `officialSiteId`, `officialLabel`, `officialFetched`, `officialDistanceM`, `notes`. |
| `retiredOn` | date or null | Kept for one release after retirement so old events still resolve. |

**2.2.4 Precedence (enforcing: `build_dataset.py::resolve_direction`, `::resolve_limit`).**

- Direction: official description (`"en direction nord"`, `"Direction: Southbound"`, Chicago
  `first_approach`/`second_approach`; compound French `sud-ouest` = 225°) → relation `from`/`to`
  member geometry (bearing from the `from` way toward the `device` node) → node `direction` tag,
  parsed as numeric, cardinal (`SSW` = 202.5°), range (`45-135` → midpoint), `both` → `both`,
  `forward`/`backward` → bearing of the containing way, multi-valued (`125;210;270`) → `both` →
  none. Node-tag direction is treated as **`both`-axis** (aligned or opposite accepted) because
  OSM does not fix whether it is aim or traffic (D11); only `official` and `relation` give a
  one-way direction.
- Limit: official → camera node `maxspeed` → relation `maxspeed` → snapped way `maxspeed` → none.
  Chicago floor 30 mph on unposted streets. A camera tag that disagrees with the snapped way by
  more than 0.6 km/h is recorded in `provenance.notes` and the **lower** value is used only when
  `kind` is `school_zone`; otherwise the way wins.

**2.2.5 Way snapping (enforcing: `build_dataset.py::snap_way`).** Candidate ways within 40 m of
the camera, **drivable classes only** (`motorway`, `trunk`, `primary`, `secondary`, `tertiary`,
`unclassified`, `residential`, `living_street` and their `_link`s; never `service`, `footway`,
`cycleway`, `path`); **prefer the way carrying `maxspeed`**, then the one whose bearing matches the
resolved direction, then the nearest. Store `wayHighway` so the on-way check can refuse a mainline
match for an `intersection`/ramp camera (the Deerfoot Trail / 16 Avenue NE case, 362 events).

**2.2.6 Relations (enforcing: `build_dataset.py::load_relations`).** Query
`relation[type=enforcement](bbox);out body;>;out skel qt;` alongside the node queries so member
geometry arrives in the same build. The `device` member is **deduplicated against the node set by
OSM id**; a relation adds direction and limit to an existing row rather than creating a second
camera. Relations whose `device` is not a `highway=speed_camera` node and not `enforcement=maxspeed`
are skipped and counted.

**2.2.7 Exclusions.** Nodes with `enforcement=traffic_signals` and no `maxspeed` on node or
relation → `kind=red_light_only` (3,411 events in the export). Nodes in a jurisdiction whose
calendar entry is `INACTIVE` for the whole dataset validity are **kept** with their program so the
annotation can say why they do not emit (decision 0.2).

**2.2.8 Coverage (per jurisdiction, as of the 2026-08-23 extract and the official lists fetched
2026-09-09 to 09-11).** Customer-facing wording is **"known camera"**.

| Jurisdiction | Fleet groups | Program(s) | Official list (source, fetched) | Speed status in window | Dataset coverage | Events in export (approx. by bbox) |
|---|---|---|---|---|---|---|
| Québec | YUL (17) | Transports Québec radars photo: 160 sites, 30 fixed, 130 mobile | Données Québec WFS, 2026-09-09 | Operating throughout | Official + OSM; per-site enforced direction | 14,801 |
| Ontario | YYZ (11), YOW (16) | Municipal ASE (Toronto, Ottawa, others) | Toronto/Ottawa open data, 2026-09-09 | **Ended 2025-11-14** | 171 OSM nodes kept, program `INACTIVE` | 8,592 |
| Alberta | YYC (14) | Calgary intersection safety devices; provincial mobile photo radar | Calgary open data dv2f-necx, 2026-09-09; no mobile site list | ISC **red-light only since 2025-04-01**; mobile limited to school/playground/construction zones, unlisted | ISC nodes kept `INACTIVE`; mobile OSM-only, `UNKNOWN` | 8,091 |
| British Columbia | YVR (15) | RoadSafetyBC intersection safety cameras, 35 speed-enabled | RoadSafetyBC list geocoded, 2026-09-09; confirmed 2026-09-10 | Operating; no published approach direction | Official + OSM; direction from node tag only | 2,804 |
| Illinois (Chicago) | ORD (13) | Chicago automated speed enforcement | City open data, 2026-09-09 | Operating; 6 mph threshold; 30 mph default | Official + OSM; approaches from list | in US 7,850 |
| Colorado | DEN (20) | Lakewood W Alameda Pkwy and others; state law expanding ASE (2024) | none found | Unverified → `UNKNOWN` | OSM only | in US |
| Georgia | ATL (19) | School-zone cameras (school days, 1 h before/after, 11+ mph) | Gwinnett County page | Operating with hours rule; calendar not verified | OSM only; hours rule in calendar | in US |
| Florida | MCO (18) | not checked | — | Unverified → `UNKNOWN` | OSM only | none in the accurate set |
| Saskatchewan | — | SGI photo speed enforcement | — | Offline end of May to Aug 2026 | OSM only | 72 (SK/MB) |
| Indiana, NC, OH, WY | — | none (no ASE authority outside IN work zones) | — | **No program → `INACTIVE`** | OSM nodes kept, `INACTIVE` | 22 |

### 2.3 Enforcement calendar (`src/main/resources/speedcamera/calendar.json`)

**Schema:** `{ programId, jurisdiction, kinds: [..], activeFrom, activeTo, hoursRule: null | {daysOfWeek, localStart, localEnd, schoolCalendar: null|ref}, toleranceKnots, toleranceSource, source: {url, checked}, notes }`.
**Semantics (enforcing: `EnforcementCalendar.statusOn(programId, instant, lat, lon)`):** `ACTIVE`
when inside `[activeFrom, activeTo)` and the hours rule (evaluated in the camera's local time
zone) passes; `INACTIVE` when outside or when the entry is a verified-absent program; `UNKNOWN`
when `programId` is null or has no entry. `UNKNOWN` emits (decision 0.4). **Fallback tolerance
5 km/h (2.70 knots)**, the same value as stage A's `event.speedCamera.buffer` (decision 0.11), for `UNKNOWN` and for entries without a published or assumed value.
`EnforcementCalendar.toleranceKnots(programId)` returns the entry's value or the fallback.

| programId | Jurisdiction / kinds | activeFrom | activeTo | Hours | Tolerance | Source (checked) |
|---|---|---|---|---|---|---|
| `qc_transports_quebec` | Québec fixed and mobile radars photo | 2011-11-01 | open | none | **10 km/h assumed** (not published) | transports.gouv.qc.ca emplacements page + WFS (2026-09-11) |
| `on_municipal_ase` | Ontario municipal ASE | — | **2025-11-14** | none | n/a | ottawa.ca ASE changes; guelph.ca 2025-11-14 shut-off (2026-09-11). User states Bill 56 passed 2025-10-30 — *unverified*, does not change the window. |
| `ab_isc_speed` | Alberta intersection safety devices, speed function | — | **2025-04-01** | none | n/a | alberta.ca/photo-radar-alberta; Global News 11110370 (2026-09-11) |
| `ab_mobile_photo_radar` | Alberta mobile, school/playground/construction zones only | 2025-04-01 | open | none | 10 km/h assumed | same as above; no site list → rows stay `UNKNOWN` unless the OSM node is inside such a zone |
| `il_chicago_ase` | Chicago fixed cameras, school and park zones | per-camera go-live from the city list | open | none | **6 mph (5.2 kn) published** | City of Chicago list; WTTW 2025-02-19 on the 30 mph default (2026-09-11) |
| `bc_isc_speed` | B.C. speed-enabled intersection cameras | — | open | none | 20 km/h assumed ("well above the limit") | news.gov.bc.ca 2026AG0062 (2026-09-10) |
| `sk_sgi_photo_speed` | Saskatchewan | — | open, **inactive 2026-05-31 to 2026-08-31** | none | fallback 5 km/h | Global News 11997164 (2026-09-11) |
| `ga_school_zone` | Georgia school-zone cameras | — | open | school days, 1 h before to 1 h after classes; school calendar **not verified** | 10 mph (fires at 11+) | Gwinnett County police page; statute amended 2026-07-01, text not retrieved |
| `in_none` | Indiana (no ASE outside interstate work zones) | — | — | — | — | WFYI (2026-09-11) → `INACTIVE` |
| `us_no_ase_nc_oh_wy` | North Carolina, Ohio, Wyoming | — | — | — | — | *source not recorded in the audit; to be added by the owner* → `INACTIVE` |
| `co_lakewood` | Lakewood, CO | — | open | none | fallback 5 km/h | lakewood.municipal.codes LMC 10.04.040 (2026-09-11) → `UNKNOWN` (code allows school zones, red lights, rail crossings; site on no list) |
| *(no entry)* | anything else | | | | fallback 5 km/h | → `UNKNOWN`, emits |

### 2.4 Runtime components

**`SegmentBuilder`** (new, `org.traccar.speedcamera`) — `build(previous, current)` returns a
`Segment{fromLat, fromLon, toLat, toLon, dtSeconds, lengthM, bearingDeg, speedKnots}` or `null`.
- `previous` = `cacheManager.getPosition(deviceId)` (`CacheManager.java:97`, the same source
  `DistanceHandler` uses).
- **Caps** (enforcing: `SegmentBuilder.build`): `dtSeconds > event.speedCamera.segment.maxSeconds`
  (default 60) or `lengthM > event.speedCamera.segment.maxMetres` (default 1,000) → `null`, counter
  `segmentRejected`. Batch/offline uploads therefore never produce kilometre-long segments; the
  current fix is then evaluated as a point (radius only).
- `bearingDeg` from the segment when `lengthM >= 10`, else from `current.getCourse()`; a course of
  exactly `0` with `speed > 0` is treated as **unknown**, not north (0.9 % of moving fixes).
- `speedKnots` = **max**(previous speed, current speed).

**`CameraDataset`** (new, `@Singleton`) — loads the bundled file (or the override path) once at
startup; buckets rows on a 0.01° grid; `candidates(lat, lon, radiusM)` returns **every** camera
within `radiusM` of the point (3×3 cells). Lifecycle: `loaded` (normal) or **startup failure**
(bad file). There is no `never-loaded` running state (decision 0.5). Optional
`event.speedCamera.dataset.refreshHours` re-reads the *file* (for hotfix overrides), never Overpass.

**`SpeedCameraMatcher`** (new) — `match(segment, dataset, cfg)` returns `Match{camera,
distanceM, bearingOk, onWay}` or `null`, in this order (enforcing: `SpeedCameraMatcher.match`):
1. `dataset.candidates(midpoint, radius + lengthM/2)` then keep cameras whose
   **point-to-segment distance** ≤ `event.speedCamera.radius` (default **30 m**; D9 makes a small
   radius safe because the segment covers the path).
2. **Direction filter first** (D12): `DirectionCheck.accepts(camera, bearing, tolerance)` —
   `directionMode=none` → accept and `bearingOk=null`; `both` → accept if `Bearing.diff(bearing,
   direction) ≤ tol` **or** `≥ 180 − tol`; `one_way` → accept only if `≤ tol`. `Bearing.diff` is
   modular (10 vs 350 = 20). Unknown bearing → accept, `bearingOk=null`.
3. **On-way check**: `WayCheck.onCameraWay(segment, camera, 15 m)` — the segment's nearest point
   must lie within 15 m of `wayGeometry`; if `camera.kind` is `intersection` and the segment's
   nearest way class is `motorway`/`trunk` (from the segment being > 15 m from the camera's
   non-mainline way), reject. Cameras with no `wayGeometry` pass with `onWay=null`.
4. **Then nearest** among survivors by point-to-segment distance.

**`SpeedCameraHandler`** (new position handler, inserted after `SpeedLimitHandler` in
`ProcessingHandler`) — on every valid position: build the segment, run the matcher, and write
`speedCameraId`, `speedCameraDistance`, `speedCameraBearingOk`, `speedCameraLimit` (knots),
`speedCameraProgramStatus`, `speedCameraKind` on the position **regardless of program status**
(decision 0.2). No Overpass call, no gate.

**`SpeedCameraEventHandler`** (rewritten, keeps name and position in the event chain) —
`shouldFire(position, match, status, tolerance)` is the single enforcing function for the rule:
- `speedCameraLimit` present and `> 0` (else skip, counter `readNoLimit`);
- `status != INACTIVE` (skip, counter `programInactive`, log at DEBUG with program and camera);
- `PlausibilityCheck.accepts(segment)`: speed ≤ 180 km/h (97.2 kn) and, when the segment exists,
  reported ÷ distance-over-time speed within 0.5–1.35;
- `speedKnots > limitKnots * (1 + thresholdMultiplier) + toleranceKnots` where tolerance comes
  from the calendar (fallback 2.70 kn = 5 km/h, the stage A buffer value);
- not locked: `SpeedCameraState.isLocked(cameraId, now, lockSeconds)` — one event per device per
  **camera id** per `event.speedCamera.lockSeconds` (default 300).
State: `SpeedCameraState{ Map<cameraId, lastEmitEpochMs> }`, written with
`setWithTTL(key, json, lockSeconds * 2)` (600 s default); old-format JSON is discarded on read.
Event payload (Traccar units): `speed` (kn), `speedLimit` (kn), `deviceSpeed` (km/h, **deprecated
duplicate for one release**, decision 0.6), `overBy` (kn), `toleranceApplied` (kn),
`speedCameraId`, `speedCameraDistance` (m), `speedCameraKind`, `limitSource`, `limitConditional`,
`directionChecked` (true when `bearingOk` was non-null), `programId`, `programStatus`
(`active`|`unknown`), `datasetVersion` (the file's `generated` stamp).

**`OverpassSpeedLimitProvider`** (stage C, `deviceOverspeed` only after stage B): query
`way[maxspeed](around:R,lat,lon);out geom;`, pick the way nearest the position, tie-broken by heading
alignment; `R` 100 → 30 m; add the 3-decimal Redis cell cache. Ships separately with its own
before/after count.

**Toll provider cleanup (stage B release, D13):** remove the `node(around:100,…)` clause from
`OverPassTollRouteProvider.java:50` and the `highway`/`enforcement` override logic (`:150-163`);
stop stamping `KEY_HIGHWAY`/`KEY_ENFORCEMENT` in `PositionInfoHandler.java:224-230`. Nothing else
reads them (verified 2026-09-13). `TollData.getHighway/getEnforcement` go with them.

**Frontend:** `EventReportPage.jsx:405-417` — extend the `speedLimit` and `speed` cases to
`speedCamera`; tooltip shows `limitSource`, distance, `programStatus`. The FE must read **both
payload shapes** (historical `deviceSpeed` km/h + `speedLimit` kn, new `speed` kn) until stage D
has tagged history. `templates/*/speedCamera.vm`: speed and limit through the speed-unit helpers.

### 2.5 Config keys (all declared in `Keys.java` with javadoc)

| Key | Default | Purpose |
|---|---|---|
| `event.speedCamera.radius` | 30 | metres, point-to-segment match distance |
| `event.speedCamera.directionTolerance` | 60 | degrees; `-1` disables (V2 decides the final value) |
| `event.speedCamera.buffer` | 5 | **km/h** over the limit before a detection fires (stage A, decision 0.11); stage B adds per-program tolerance on top or in its place (§2.3, open) |
| `event.speedCamera.thresholdMultiplier` | 0 | fraction over the limit, applied before the absolute tolerance |
| `event.speedCamera.lockSeconds` | 300 | per device per camera id |
| `event.speedCamera.segment.maxSeconds` | 60 | segment cap |
| `event.speedCamera.segment.maxMetres` | 1000 | segment cap |
| `event.speedCamera.dataset.file` | bundled resource | override path for a hotfix dataset |
| `event.speedCamera.dataset.refreshHours` | 0 (off) | re-read the file |
| `event.speedCamera.calendar.file` | bundled resource | override path |
| `event.speedCamera.wayTolerance` | 15 | metres, on-way check |

Removed after one deprecation release (kept as no-ops that log a WARN at startup):
`event.speedCamera.highwayTypes`, `event.speedCamera.enforcementTypes`. There is **no**
`event.speedCamera.source` flip-back flag (r1 had one; it contradicted B — see change log).

---

## 3. Stages and order

| Stage | Scope | Notes |
|---|---|---|
| **A — Hotfix** (ship first, alone) | D1, D2, D6, half of D7: compare in knots (`position.getSpeed() > limitKnots + bufferKnots + SPEED_EQUALITY_EPSILON_KNOTS`; **buffer = `event.speedCamera.buffer`, 5 km/h default, decision 0.11**; epsilon **0.01 kn**: an equality guard for km/h-to-knots conversion noise, not a tolerance — see build note 7.2), treat `speedLimit <= 0` or absent as missing with a `readNoLimit` counter (counted only inside a camera zone), write `speed` + `speedLimit` in knots and keep `deviceSpeed` km/h; FE case for `speedCamera` reading both payload shapes; templates print speed and limit; state key written with **`setWithTTL(key, json, 3600)`** (the state carries only the 60 s highway lock, one hour is ample). ~40 lines, trivially reviewable. **Built 2026-09-16 on `riq-speed-camera-fix`, buffer added 2026-09-18; V1-A = 7,567 exactly (11,638 at buffer 0).** In review: SquareOneYYZ/Union PR #149, Union-fe PR #180. | Expected prod rate after A under the current gate: 17.9 % × 462 ≈ **83/day** (27.6 % ≈ 125/day without the buffer; r1 said "~80/day of 292"). Acceptance §4.1 V1-A. |
| **B — Segment matching against the curated dataset** | `build_dataset.py`, `cameras.json`, `calendar.json`, `SegmentBuilder`, `CameraDataset`, `SpeedCameraMatcher`, `SpeedCameraHandler`, rewritten `SpeedCameraEventHandler`, toll-provider cleanup (D13), removal of D8 keys. **No flip-back flag: revert is the rollback.** | Depends on A's payload shape and on the owner being named (§2.2.1). |
| **C — Nearest-road limit for `deviceOverspeed`** | `OverpassSpeedLimitProvider` geometry pick + cell cache (D3). | Independent of B (principle 3). Own before/after count; 45–55 k events/day are affected. |
| **D — Historical events** | *Procedural, user decision.* Tag, never delete: `UPDATE tc_events SET attributes = JSON_SET(attributes,'$.suspect',true) WHERE type='speedCamera' AND eventtime < '<stage A deploy time>' AND (JSON_EXTRACT(attributes,'$.speedLimit') IS NULL OR JSON_EXTRACT(attributes,'$.speedLimit') = 0 OR JSON_EXTRACT(attributes,'$.deviceSpeed') <= JSON_EXTRACT(attributes,'$.speedLimit') * 1.852);` — the `eventtime` bound makes it idempotent, `IS NULL` catches absent limits. | ~30,600 of 42,210 rows in the window are known not to be fine exposure. |

**Dependency note (procedural, decision 0.10):** the **Routes speed-zone event builds on
`speedCamera`.** It and its **historical pull** ship **after stages A and D**: A for the payload
shape, D for the tagged history. **The historical pull excludes stage D suspect rows**: its query
carries `AND JSON_EXTRACT(attributes,'$.suspect') IS NULL`, so it never returns the ~30,600 rows
that are not fine exposure. (No ticket id was supplied; the user confirmed the event type and the
sequencing on 2026-09-16.)

Each stage: unit tests → offline replay (§4) → staging soak → fresh-reviewer gate → prod.

---

## 4. Validation — every data point, not a sample

### 4.1 Offline harness `scripts/speed_camera/replay_positions.py`

Mirrors `scripts/toll_research/replay_window.py`: semantics copied from the source with line
references so the harness and the code cannot drift silently. Inputs: a positions TSV (the day
export columns), `cameras.json`, `calendar.json`. Outputs: one verdict row per position under the
old rule and the new rule, and a per-device diff.

**Independent pass definition (D9).** Before any rule is applied the harness counts **passes**: a
device's consecutive-fix segment chain (with the same caps as `SegmentBuilder`) comes within
`passRadius` (default 30 m, point-to-segment) of a dataset camera; one pass per device per camera
per 5 min. A pass is a property of the track, not of the detection rule, so "every pass produces
exactly one event" is testable. Then: passes over the limit, passes in the enforced direction,
events, misses, and events without a pass.

| # | Dataset | Question | Acceptance |
|---|---|---|---|
| V1-A | Pinned export, 42,210 event positions | Stage A regression pin: the hotfix rule reproduces two independent references from the audit file: `verdict_unit_fix_only` at buffer 0, and `speed_kmh − stored_limit_kmh > 5` on the audit's own km/h columns at the default buffer. | **7,567 ± 1 % at buffer 5 km/h** and **11,638 ± 1 % at buffer 0** (continuity). Pins the rule against the spreadsheet that produced it; not validation of correctness. **Result 2026-09-18: 7,567 (+0.00 %) and 11,638 (+0.00 %); 42,210 of 42,210 rows agree on both checks; old rule reproduces all 42,210.** Output `data prod/stage-a/v1a_summary.md`, `v1a_verdicts.tsv`. |
| V1-B | Same | Stage B rule at 100 m point radius without official overlay / with overlay / with calendar, direction, plausibility, tolerance | **7,030 / 4,477 / 2,361 fixed** ± 1 %, with **1,347 mobile-site** events reported beside them and not counted toward acceptance (decision 0.9); strict ticket rule **44 + 218**. |
| V2 | `positions_sep3.tsv`, `positions_sep8.tsv` (all moving fixes, all devices) | How many passes exist (segment definition) vs how many a 50 m / 100 m point radius sees; how many the gate hid; what the new rule fires. **This is the stage B acceptance for "all data points".** | Zero events without a pass; zero events with `distance > radius`; zero events failing the direction check; every pass over limit+tolerance in the enforced direction at an active-or-unknown program produces exactly one event. **Acceptance is computed on fixed-site cameras; mobile-site passes and events are reported in a separate column and feed the `emittedMobileSite` counter check** (decision 0.9). Report the Sep 3 / Sep 8 event counts beside the 223 / 452 that prod fired. Decide `directionTolerance` and `radius` here. |
| V3 | Same days, `deviceOverspeed` positions | Stage C side-effect: how many limits change when the nearest road replaces the first. | Reported, no threshold. |
| V4 | Staging drive-through, **Québec fixed camera from the official list**: Transports Québec "Route 138 en direction est, entre le pont Mercier et l'autoroute 20 [Radar photo fixe]", OSM node 5382163527, **45.4287833, -73.6494035**, official site 8 m from the node, 213 real events in the export | End-to-end on staging: dataset loads, handler annotates, one event eastbound over limit + tolerance, **none westbound**, none under the limit, none for a second pass inside `lockSeconds`. | Exactly as stated. `tools/speedCamera.py` gets a Route 138 track; the Brampton track (Ontario, camera removed) is deleted. |

### 4.2 Unit tests (`src/test/java/org/traccar/handler/events/SpeedCameraEventHandlerTest.java` and `src/test/java/org/traccar/speedcamera/*Test.java`)

| # | Case | Pins |
|---|---|---|
| T-1 | limit absent → no event, `readNoLimit` incremented | D2 |
| T-2 | limit 26.998 kn, speed 20 kn (37 km/h in a 50 zone) → no event | D1 |
| T-3 | limit 26.998 kn, speed 30 kn (5.56 km/h over, clears the 5 km/h buffer) → event with `speed`/`speedLimit` in knots and `deviceSpeed` km/h duplicate | D1, D6, 0.6, 0.11 |
| T-3b | **equality guard**: speed 26.9979 kn vs limit 26.9978 kn (50 km/h through two conversions) → no event; 0.26 kn over → event | build note 7.2 |
| T-4 | **buffer**: limit 50 km/h, speed 54.9 → no event; 55.0 → no event (at the buffer); 55.1 → event; `event.speedCamera.buffer = 10` → 59.9 no, 60.1 yes (stage A, decision 0.11) | 0.11 |
| T-5 | **direction wrap**: camera `one_way` 350°, bearing 10° → accept; bearing 190° → reject | D11 |
| T-6 | **filter-then-nearest**: two `one_way` cameras 20 m apart facing 90° and 270°, segment bearing 88°, the 270° one nearer → matches the 90° camera | D12 |
| T-7 | **segment match with caps**: camera 25 m off the segment midpoint, 140 m from both fixes → event at radius 30; same with `dt` 61 s → no segment, point-only, no event; same with length 1,001 m → no event | D9 |
| T-8 | `course = 0`, `speed > 0`, segment < 10 m → bearing unknown, `bearingOk` null, event allowed | course sentinel |
| T-9 | camera `kind=intersection`, snapped way `secondary`, segment on `motorway` geometry 30 m away → no match | Deerfoot |
| T-10 | camera `maxspeed=40` overrides road 60 → fires at 45 km/h + tolerance; `limitConditional` true → base value used and flag set | limits |
| T-11 | same camera twice within `lockSeconds` → one event; different camera → two; state written with TTL 600 | D7 |
| T-12 | dataset file with `schemaVersion` 99 or zero cameras → startup failure, not an empty index | 0.5 |
| T-13 | **inactive program** (Ontario on 2026-05-01; Alberta ISC on 2026-06-01) → position attributes written, `programInactive` counter, **no event** | 0.1, 0.2 |
| T-14 | **unknown program** (camera with `program=null`; camera with a program id absent from the calendar) → **event**, `programStatus=unknown`, fallback tolerance | 0.4 |
| T-15 | Saskatchewan camera on 2026-06-15 → no event; on 2026-09-15 → event | calendar windows |
| T-16 | Georgia school-zone camera at 02:00 local → no event; 08:30 on a weekday → event | hours rule |
| T-17 | 330 km/h track → no event (plausibility); reported/implied ratio 1.5 → no event | plausibility |
| T-18 | red-light-only camera (`enforcement=traffic_signals`, no limit) → annotated `kind=red_light_only`, never fires | 2.2.7 |
| T-19 | `CameraDatasetTest`: bucket lookup across a cell boundary; antimeridian guard does not throw | index |
| T-20 | `OverpassQueryUrlTest` gains the build script's node + relation query strings; `tools/check_overpass_query.sh` a read-only GET for each | build |
| T-21 | `EnforcementCalendarTest`: every entry has `source.url` and `source.checked`; every entry with a tolerance has `toleranceSource` | calendar hygiene |

Test fakes must express: dataset load failure, calendar entry missing, previous position missing,
Redis unavailable.

### 4.3 Prod metrics after each deploy (procedural)

`speedCamera` per day per group, **split by `speedCameraKind` (fixed vs `mobile_site`)**; share
with `limitSource=camera`; share with `distance > 20 m`; share `programStatus=unknown`; counters
`readNoLimit`, `programInactive`, `segmentRejected`, `emittedMobileSite`.

Baselines, all from the pinned data:

| Baseline | Value |
|---|---|
| Pre-fix, current gate (Sep 7 to 10) | **462/day** (Sep 1 to 6: 280/day; Apr 1 to Sep 10: 263/day) |
| After stage A with the 5 km/h buffer (17.9 % of the above) | **≈ 83/day** (≈ 125/day at buffer 0) |
| **Fine exposure under current sampling, fixed sites** (2,361 over 163 days) | **≈ 14.5/day** |
| **Fine exposure under current sampling, Québec mobile sites** (1,347 over 163 days; `kind = mobile_site`) | **≈ 8.3/day**, reported as its own line and never merged into the fixed figure |
| After stage B | the V2 pass count on Sep 3 / Sep 8 scaled to the fleet; **not predictable from the export** because stage B sees passes the gate hid |

Per-program shares to watch against the audit: Québec ≈ 81 % of fine-exposure events, Chicago ≈ 4 %,
B.C. ≈ 3 %, Alberta 0 %, Ontario 0 %.

---

## 5. Prod data

All items requested in r1 §5 have been delivered (see §1.2). Nothing further is needed for stages
A–D. Stage D's SQL needs the stage A deploy timestamp, known at deploy time.

---

## 6. Risks and open decisions

- **Dataset owner:** Luke (dev lead), decision 0.8. Resolved 2026-09-16.
- **Québec mobile sites:** emit as `mobile_site`, counted separately, decision 0.9. Resolved
  2026-09-16. Residual risk: some mobile-site events are passes where no unit was present; the
  kind flag lets a customer filter them.
- **Stage B fallback tolerance:** 5 km/h, matching stage A's buffer (decision 0.11). Resolved 2026-09-20.
- **Direction tolerance 60° and radius 30 m** are r2 defaults; V2 fixes them.
- **Tolerances are assumed** for Québec (10 km/h), Alberta mobile (10), B.C. (20). A wrong assumption
  moves events across the fine line, not into or out of the annotation.
- **Conditional limits**: base value used and flagged; time-rule parsing is a later stage. Some
  "40 stored / 50 real" D3 cases may be school-hour limits.
- **Map staleness**: the dataset inherits the build's OSM base; a camera added after it is invisible
  until the owner rebuilds. Same limitation as today, now with a named owner and a diff report.
- **Stage C touches `deviceOverspeed`** (45–55 k events/day). Own review, own counts.
- **Shared Redis keyspace**: local runs must override `redis.host` (developer note before stage B).
- **Historical rows**: tag or leave; never delete.

### 6.1 Not verified in r2

| Claim | Status |
|---|---|
| Ontario Bill 56 passed 2025-10-30 (user-supplied) | not checked; the calendar uses the 2025-11-14 shut-off, which is verified and earlier than the window either way |
| External consumers of `deviceSpeed` (Push API, RCR OpenAPI, iot-api) | user checking; repo has none |
| The "Routes speed-zone event and historical pull" | event type (`speedCamera`) and sequencing confirmed by the user 2026-09-16; no ticket id supplied |
| Québec, Alberta, B.C. operator tolerances | assumed; not published |
| Georgia school calendar and the 2026-07-01 statute text | not retrieved (403) |
| Source for "no ASE authority in NC, OH, WY" | not recorded in the audit; owner to add |
| Florida (MCO) camera program status | not checked; no events in the accurate set |
| Exact extract date of prod's Overpass at 147.182.153.145 | known only as "older than mid-2025" (407 East still `toll=yes`) |
| Whether `maxspeed:conditional` explains any specific D3 case | not tested |
| Lakewood, CO camera legality on W Alameda Pkwy | unverified; `UNKNOWN` → emits |

---

## 7. Change log r1 → r2 (2026-09-13)

| Change | Why | Finding / source |
|---|---|---|
| Event meaning fixed as fine exposure; inactive programs annotate but never emit; unknown = active | design decision | user decision 0.1–0.4 |
| Point-in-radius replaced by segment matching (previous fix → current), bearing from the segment, max of the two speeds, caps on dt and length; radius 50 → 30 m | a 50 m point radius catches a third of highway passes | D9, Sep 3 gap distribution (median 108 m, 272 m at speed) |
| Curated dataset file replaces the runtime Overpass index; schema, precedence, provenance, owner, "a camera change is a deploy"; server never calls Overpass for cameras | boot dependency and wrong-server risk removed; limit resolved once at build | decision 0.5; prod uses the older 147.182.153.145 extract |
| `type=enforcement` relations added to the build; device member deduped against nodes; member geometry fetched | only unambiguous direction source in OSM; 827 relations in NA, 125 in ON+QC, none seen by r1 | D11 |
| Direction precedence official > relation > node > none; node tag treated as both-axis; non-numeric forms parsed | OSM `direction` is aim, not traffic; 104 nodes carry non-numeric forms | D11 |
| Filter by direction, then on-way check, then nearest | nearest-first shadows the right camera at a quarter of sites | D12, 338 nodes with a neighbour within 30 m |
| Way snapping: drivable classes only, prefer the way with `maxspeed`; on-way check at 15 m refuses mainline traffic for intersection cameras | Deerfoot Trail / 16 Ave NE, 362 events | audit |
| Enforcement calendar with per-program tolerance, source and date; 3 km/h fallback; hours rules | zero threshold + GPS noise fires at limit+1; programs differ | user amendment; audit program table |
| Ontario split and coverage table; V4 moved to Québec Route 138 (node 5382163527) | Ontario cameras cannot ticket; r1's V4 camera no longer exists | D10 |
| `source=tags|index` flip-back flag removed | needed the old path live while B deleted it | user review, big 5 |
| Toll provider node clause and highway/enforcement stamping removed in the stage B release | dead weight polluting `highway`; nothing else reads the keys | D13, grep 2026-09-13 |
| Stage A TTL stated (3,600 s); stage B state TTL = `lockSeconds × 2` | r1 left the value out | user review |
| `deviceSpeed` kept as deprecated duplicate for one release | external consumers unknown | decision 0.6 |
| Stage D SQL: `IS NULL` added, `eventtime` bound for idempotence | absent attribute extracts as NULL | user review |
| `course == 0` with speed treated as unknown | 0.9 % of moving fixes | Sep 3 measurement |
| Export pinned (`updated camrea events.tsv`, 2026-09-09, 42,210); 27 % → 27.6 %; 292/day → 263 / 280 / 462; fine-exposure baseline 23/day; stage A expectation 80 → 125/day | r1 figures came from the superseded 39,598-row export and a pre-gate-change rate | `daily_counts.tsv`, workbook |
| Unit tests T-4 to T-9, T-12 to T-18, T-21 added | gaps named in review | user review |
| Independent pass definition in the harness | "every pass produces one event" was defined by the rule under test | user review, big 1 |
| Dependency note for the Routes speed-zone event and historical pull | sequencing | user amendment |
| Section 6.1 "not verified" added | honesty about assumptions | — |

### 7.1 r2 → r2.1 (2026-09-16)

| Change | Why | Source |
|---|---|---|
| Dataset owner named: Luke, dev lead; name in file header, role in plan | stage B was blocked on it | user decision 0.8 |
| Dataset build runs monthly in CI (`speed-camera-dataset.yml`), opens a PR with the diff report assigned to the owner; owner reviews and merges | replaces "owner runs the script by hand"; makes the cadence enforceable | user decision 0.5 amendment |
| Québec mobile sites emit with `speedCameraKind = mobile_site`; baseline split fixed 14.5/day vs mobile 8.3/day; mobile excluded from stage B acceptance counts, included in counters (`emittedMobileSite`) | exposure is real but camera presence is part-time | user decision 0.9 |
| Routes speed-zone event confirmed to build on `speedCamera`; dependency on A and D stands; historical pull filters out stage D `suspect` rows | keeps ~30,600 non-exposure rows out of the customer pull | user decision 0.10 |

### 7.2 Stage A build notes (2026-09-16, branch `riq-speed-camera-fix`, not yet reviewed)

| Note | Detail |
|---|---|
| **Equality guard added to the knots compare** | First V1-A run with a strict `speed > limit` gave 12,177 (+4.6 %). All 539 extra rows were vehicles at exactly the posted limit whose speed and limit differ in the fifth decimal (50 km/h: 26.9979 vs 26.9978 kn) because protocol decoders and `UnitsConverter.knotsFromKph` convert km/h with slightly different constants. The smallest genuine over-limit reading in the agreed set is 0.26 kn. `SPEED_EQUALITY_EPSILON_KNOTS = 0.01` (0.02 km/h) separates the two by two orders of magnitude on each side. With it V1-A is 11,638 exactly and every row agrees with the audit. This is not an operator tolerance; those stay in stage B's calendar. **Reviewer to confirm.** |
| `readNoLimit` counts only inside a camera zone | Counting every position without a limit would count the 20 % of fixes the ungated limit provider misses; the plan's intent is "camera seen, no limit". |
| Old rule reproduces the export | The harness's copy of the pre-fix rule fires on all 42,210 exported rows from their stored attributes, so the export is a faithful record of what prod evaluated. |
| Export `valid` column is empty | All 42,210 rows carry an empty `valid`; they fired, so they were valid at the time. The harness does not filter on it. |
| Not done in stage A | Nothing else from stages B–D; `highwayTypes`/`enforcementTypes` stay undeclared (D8) until B. |

### 7.3 r2.1 → r2.2 (2026-09-18)

| Change | Why | Source |
|---|---|---|
| 5 km/h detection buffer from stage A on, as declared key `event.speedCamera.buffer` (km/h, default 5), converted once to knots and added to the compare; equality guard kept underneath it | user wants a buffer on detections | user decision 0.11 |
| V1-A acceptance becomes 7,567 at the default buffer with 11,638 kept as the buffer-0 continuity check; both references computed independently from the audit file | the pin must follow the rule | harness, audit km/h columns |
| Expected prod rate after A: ≈ 83/day (17.9 % of 462) | follows from the buffer | daily counts |
| Tests: T-4 rewritten for the buffer (4.9 / 5.0 / 5.1 km/h and a 10 km/h override); T-3b now runs on a zero-buffer handler | pin the buffer and keep the guard pinned separately | build |
| Stage A PRs: SquareOneYYZ/Union #149 (fix + docs commits), Union-fe #180; targets are the SquareOneYYZ masters | user: there is no Rides-IQ git to PR into | user 2026-09-18 |
| Open: stage B fallback tolerance 3 vs 5 km/h | A and B should agree where no program value exists | this revision |

### 7.4 r2.2 → r2.3 (2026-09-20)

| Change | Why | Source |
|---|---|---|
| Stage B calendar fallback tolerance 3 → 5 km/h (2.70 kn), matching stage A's buffer; §2.3 entries, `shouldFire` note and T-3 updated | stages A and B must agree where no program value is published | user decision 2026-09-20 |
| Dataset PR assignee `lakha-riq` confirmed | the plan carried it as stated, not confirmed | user 2026-09-20 |

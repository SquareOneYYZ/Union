# 22 — Where the estimate lives: D1 revisited

> **Note, 2026-09-08.** Still the standing recommendation for D1 (pending). `20` revision 2 adds one column this design needs: `mergedinto`, for the fragment-merge rule that the 4 × 250 m production window makes necessary on tag-gapped corridors such as the Beachline.

**Date:** 2026-09-07 · **Reopens:** D1 from `20-mvp1-scope.md` §0 · **Status:** recommendation,
not decided, no code written.

The user's direction on 2026-09-07: the estimate does not have to be on the events table if a
better design exists. This document compares the options against the source and recommends
one. If accepted, `20` §0 (D1), §2.2, §2.6 and `21` Gaps 1, 3, 6, 9 change — the last section
lists exactly how.

Verified this session: `tc_events.attributes` is `VARCHAR(4000)`
(`schema/changelog-4.0-clean.xml:171`); `scripts/archive_cold_storage.py` **deletes**
`tc_positions` and `tc_events` past retention and **snapshots without ever deleting**
`tc_geofences`, `tc_drivers`, `tc_devices`, `tc_device_geofence_segment` (its own header,
`:11-12`); `tc_device_geofence_segment` (`schema/changelog-6.6.5.xml`) is written by
`GeofenceEventHandler`, read by `DeviceGeofenceDistanceResource` with
`permissionsService.checkPermission(Device.class, getUserId(), deviceId)` (`:67, :96`), and
reported by `DeviceGeofenceDistanceReportProvider`.

---

## 1. Why the events table is the worse home

Four things, each verified, each independent of the others:

1. **The 4,000-character cap.** Everything the exit already carries plus every `tollCost*`
   attribute plus the inputs JSON must fit or the insert fails and the *event* is lost — not
   just the estimate. `21` works around it with compact encodings; a table has no such limit.
2. **Cold storage deletes events.** `tc_events` is in the delete list. An estimate stored there
   disappears with its event at the retention window (default six months). A cost record is
   the one toll artefact that should outlive the positions it was computed from.
3. **Aggregation has to parse JSON.** "Toll spend by facility by month" becomes `JSON_EXTRACT`
   over a string column, unindexed, for every consumer that wants a total. Columns make it a
   `SUM ... GROUP BY`.
4. **Re-estimation rewrites history in the wrong place.** Bumping the calc version means
   `UPDATE tc_events SET attributes = ...` on rows that may already be exported to parquet, so
   the archive and the database diverge silently.

Two more that are design rather than storage: an event is one row per edge, so an open
traversal ("on the 407 since 08:12") has nowhere to live, and the entry mark that `21` Gap 1
struggles to preserve across a Redis flip is exactly what an open row would hold.

---

## 2. Options

| | Option | Size cap | Survives archival | Aggregation | Re-estimate | Open traversals | Cost |
|---|---|---|---|---|---|---|---|
| **1** | Attributes on the exit event (`20` as written) | 4,000 chars shared | ✗ deleted with events | JSON parse | rewrite event rows | ✗ | lowest: no DDL |
| **2** | Dedicated table `tc_toll_traversal`, nothing on the event | none | ✓ (snapshot list) | columns | `UPDATE` + `calcversion` | ✓ | changelog + model + handler writes + resource + report + cold-storage entry + IoT API entity |
| **3** | **Table as system of record + four headline attributes on the exit event** | headline < 120 chars | ✓ | columns | table only; event keeps the original headline | ✓ | option 2 + four `event.set` calls |
| **4** | Option 3 plus an estimates-history child table (`traversalid`, `calcversion`, `amount`, `pricedat`) | none | ✓ | columns | insert a row per version, never overwrite | ✓ | option 3 + one more table and model |

Option 1 is what `20` chose before the cap and the deletion were known. Option 4 is option 3's
upgrade path once someone needs to see what an estimate *was* before a rule change; nothing
in MVP1 needs that, and the inputs are the same across versions, so it is deferred.

**Recommend option 3.** The table is the record. The event carries just enough that existing
consumers — notifications, the events list, the IoT API's event feed — show a number without
a join, and a `tollTraversalId` to reach the rest.

---

## 3. `tc_toll_traversal` — modelled on `tc_device_geofence_segment`

Same lifecycle as the geofence segment: **insert an open row at enter, close and price it at
exit.** Same naming conventions, so the resource and report can be copied rather than designed.

```
id                 INT auto           
deviceid           BIGINT             idx
facilityid         VARCHAR(64)        registry id, idx     ("407-etr", "cfx", "mto-407e", or NULL until matched)
open               BOOLEAN            idx with deviceid    (at most one open row per device)

enterpositionid    BIGINT             UNIQUE with deviceid (idempotency: re-runs and backfill cannot duplicate)
entertime          DATETIME           idx with deviceid    (position fixTime, UTC)
enterlat, enterlon DOUBLE
exitpositionid     BIGINT NULL
exittime           DATETIME NULL
exitlat, exitlon   DOUBLE NULL
odostart, odoend   DOUBLE             KEY_TOTAL_DISTANCE at each edge
distance           DOUBLE NULL        odometer path distance (what tollDistance is today)

-- pricing, all NULL until priced; NULL means unknown, never 0
corridordistance   DOUBLE NULL        distance along the corridor (distance_zoned) or trail-in-polygon (per_distance)
direction          VARCHAR(16) NULL
daytype            VARCHAR(24) NULL   weekday | weekend_or_holiday
band               VARCHAR(16) NULL   card's band label
vehicleclass       VARCHAR(32)
paymentmethod      VARCHAR(32)
amount             DECIMAL(10,2) NULL
currency           CHAR(3) NULL
tier               CHAR(1) NULL       A | B | C
reason             VARCHAR(48) NULL   set only when amount is NULL
calcversion        INT NULL
cardvalidfrom      DATE NULL
pricedat           DATETIME NULL      when the estimate was written; emission lag = pricedat − exittime
inputs             TEXT               JSON: zone km, passages [clusterId, seconds], breakdown by facility, file versions, osm_base
```

Indexes: `(deviceid, entertime)`, `(deviceid, open)`, `(facilityid, entertime)`, and the unique
`(deviceid, enterpositionid)`. Roughly 300 rows a day in production (`TOLL-RESUME.md` §0), so
~110 k rows a year. No partitioning.

**`DECIMAL` for money**, not `DOUBLE`. `19` B8 showed `QueryBuilder` reads a `double` column
as `0.0` when NULL and has no `BigDecimal` path; the model must use a boxed type and the
storage mapping must be checked for that one column — that is the single piece of
"null-preserving" work MVP1 cannot skip, and it is one column.

### Lifecycle, and who writes it

| moment | in `TollEventHandler.onPosition` after `updateState` | row |
|---|---|---|
| enter event emitted | insert `{open=true, enter*, odostart}`; put the new `id` in `TollRouteState` (`traversalId`) | open |
| any position while `tollStartDistance > 0` (flat facilities only) | passage detected → append to the passage list held in state; **no write** | — |
| exit event emitted | find the row: `state.traversalId`, else `WHERE deviceid=? AND open=true` (this is the Redis-flip fallback — the entry mark is in the row, `21` Gap 1 dissolves); set exit*, distance, run the estimator, set pricing columns, `open=false`; `event.set` the four headline attributes | closed, priced |
| re-estimation (later job) | `UPDATE` pricing columns with a higher `calcversion`; the event keeps the original headline | closed, re-priced |
| enter with an open row already present (C8 re-fire, or a lost exit) | close the stale row with `reason=SUPERSEDED`, then insert the new one; count it in the aggregate log line | — |

Writes on the hot path: one insert per enter, one update per exit. The handler already does a
device-row update on every state change (`onPosition:320-328`), so this is a smaller write
budget than what exists.

### What stays on the exit event

Four attributes, under 120 characters together, well inside the 4,000:

```
tollTraversalId    long
tollCostEstimate   double   (absent when unknown)
tollCostCurrency   string
tollCostTier       string
```

Reason codes, inputs, breakdowns and versions live only in the table. The events list shows a
number; anything deeper is one lookup by id.

### Read paths

- **`GET /api/tolltraversals?deviceId&from&to&facilityId&open`**, a copy of
  `DeviceGeofenceDistanceResource`'s shape with the same per-device permission check.
- **`/api/reports/tolltraversals`** as a report provider, grouped by facility and currency —
  copy of `DeviceGeofenceDistanceReportProvider`'s shape. Never sums across currencies.
- **IoT API**: a `TollTraversalsEntity` on the new table, exposed beside events; the events feed
  still shows the headline attributes without any change there.

### Cold storage

Add `tc_toll_traversal` to the script's **SNAPSHOT** list (store, never delete), exactly as
`tc_device_geofence_segment` is handled (`snapshot_device_geofence_segments`, `:600-603`).
The archive then carries the cost history in parquet as well, and re-estimation runs on the
live table only.

---

## 4. What it costs over the events-only design

| item | copy from | size |
|---|---|---|
| `schema/changelog-6.6.x.xml` | `changelog-6.6.5.xml` (segment table) | small |
| `model/TollTraversal.java` with `@StorageName` | `model/DeviceGeofenceSegment.java` | small |
| insert/update calls in `TollEventHandler` | `GeofenceEventHandler`'s segment writes | small |
| `api/resource/TollTraversalResource.java` | `DeviceGeofenceDistanceResource.java` | small |
| `reports/TollTraversalReportProvider.java` | `DeviceGeofenceDistanceReportProvider.java` | medium |
| cold-storage snapshot entry | `snapshot_device_geofence_segments` | small |
| IoT API entity + endpoint | `EventsEntity` | small, other repo |

About two days on top of `20` §5 WP2, most of it copying a pattern that has shipped and been
reviewed once already. It removes work elsewhere: `21` Gap 1 fixes A and B are no longer
needed, Gap 3's passage list no longer has to fit a 4,000-character budget, Gap 6's backfill
becomes plain inserts, and the size-budget unit test in `21`'s constraint section goes away.

---

## 5. Risks specific to this design

- **Two writes can disagree.** If the table update succeeds and the event insert fails (or
  the reverse), the headline and the record differ. Mitigation: the table is the record; the
  event's headline is a cache. The aggregate log line counts mismatches; the re-estimation job
  can re-stamp events.
- **An open row without an exit lives forever** when a device dies mid-traversal. Mitigation:
  the report shows `open=true` rows older than 24 h as "unclosed" with no amount; the
  detection track decides whether to close them (`21` Gap 9 B) — pricing never guesses.
- **Money in a `DECIMAL` through a storage layer that has no `BigDecimal` path.** Verify
  `QueryBuilder`'s handling on this one column before the first insert, in a test that writes
  NULL and reads NULL back. If the layer cannot do it, store cents as a nullable `BIGINT`
  (`amountcents`) — an integer column round-trips NULL through the boxed `Long` path.
- **Permissions.** The resource must check device access per row exactly as the geofence
  distance resource does; an organisation-wide total (`tc_organization` exists) needs its own
  check. MVP1 exposes per-device and per-user-devices only.

---

## 6. If accepted, what changes upstream

| document | section | change |
|---|---|---|
| `20` | §0 D1 | "on the exit event" → "in `tc_toll_traversal`, with a four-attribute headline on the exit event" |
| `20` | §2.2 | `entryMark` still added to state (it is the in-memory copy), but the durable copy is the open row |
| `20` | §2.6 | replaced by §3 of this document; reason codes move to the table |
| `20` | §5 | WP2 gains the table, resource, report and cold-storage entry; WP4's passage list is unconstrained by size |
| `20` | §6 | "a `toll_traversal` table or any DDL" leaves the out-of-scope list |
| `21` | constraint section | dropped |
| `21` | Gap 1 | dissolved: the open row is the entry mark |
| `21` | Gap 3 | A still recommended; the 4,000-char budget no longer applies |
| `21` | Gap 6 | A becomes "insert rows", C still the launch stance |
| `21` | Gap 9 | open rows are visible, so the lag is observable without extra fields |
| `12-data-model.md` | `toll_traversal` / `trip_toll_estimate` | this is the (iii) traversal-owned identity `16` D1 recommended, collapsed to one table for MVP1; the estimate-history child table (option 4) is the split `12` drew |

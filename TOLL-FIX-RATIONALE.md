# TOLL-FIX-RATIONALE.md — what shipped, line by line

Written from the diff `master..riq-toll-stage1-fix`, using post-change line numbers.
`TOLL-FIX-PLAN.md` describes intent; this describes what is in the tree.

Five commits, two branches:

| branch | commit | contents |
|---|---|---|
| `riq-toll-stage0-repro` | `d24c731` | Stage 0 — 23 assertions, 13 red on master |
| `riq-toll-stage1-fix` | `aa7663a` | 1a + 1b, one atomic change across three files |
| | `fca98ae` | secondary defect: event type inferred from the odometer |
| | `0162bec` | 1c — backdate to the traversal boundary |
| | `ef2f659` | 1d — the gate becomes a config key |

Files touched: `Keys.java` (+20), `PositionInfoHandler.java` (+43/−6),
`TollEventHandler.java` (+31/−2), `TollRouteProcessor.java` (+96/−21),
`TollRouteState.java` (+172/−2). 588 tests green, `checkstyleMain` clean.

---

## Part A — every changed hunk, justified

### A1. `PositionInfoHandler.java:25-36` — two attribute-name constants

```java
    /**
     * Set on a position whose enrichment lookup the distance gate skipped. ...
     */
    public static final String KEY_TOLL_LOOKUP_SKIPPED = "tollLookupSkipped";

    /**
     * Set when the Overpass lookup itself failed. Mirrors {@code regionLookupFailed} ...
     */
    public static final String KEY_TOLL_LOOKUP_FAILED = "tollLookupFailed";
```

**Role.** These are the write-side half of a contract whose read side is
`TollEventHandler.readToll` (`:78`). They are `public` because the two ends live in different
packages and the whole defect was two components disagreeing about what an absent value meant;
a string literal repeated in both files would let them drift apart again silently.

**Remove them** and the two files hold independent literals. Nothing fails at compile time and
nothing fails at test time until someone edits one spelling — at which point every gated-out
position starts reading as `false` again and the fleet goes quiet with no error. That is the
original defect, restored by a typo.

**Alternative rejected.** Putting them in `Position.java` beside `KEY_TOLL`. They are not
device telemetry and not part of the position schema anyone reports on; they are internal
handshakes between two handlers. `regionLookupFailed` sets the local precedent
(`PositionInfoHandler.java:110`, read at `RegionEventHandler.java:39`) — though as a bare
literal in both places, which is the mistake not to copy.

*Answers: plan §1a "required companion", errata item 5b's read/write asymmetry.*

---

### A2. `PositionInfoHandler.java:38-55` — the gate becomes configurable

```java
    private final double minDistanceMeters;
    private final ConcurrentHashMap<Long, double[]> lastProcessedPositions = new ConcurrentHashMap<>();

    @Inject
    public PositionInfoHandler(Config config, TollRouteProvider tollRouteProvider, RegionProvider regionProvider) {
        this.tollRouteProvider = tollRouteProvider;
        this.regionProvider = regionProvider;
        this.minDistanceMeters = config.getInteger(Keys.TOLL_ROUTE_MINIMAL_DISTANCE);
        // The gate is the only bound on external call volume, and an unset key used to make it
        // vanish silently. State the effective value at startup so it is never a guess again.
        LOGGER.info("Enrichment lookup gate: {} m ({})", minDistanceMeters,
                Keys.TOLL_ROUTE_MINIMAL_DISTANCE.getKey());
        if (minDistanceMeters <= 0) {
            LOGGER.warn("{} resolved to {} - the enrichment gate is disabled and every valid "
                            + "position will issue an Overpass and a region lookup",
                    Keys.TOLL_ROUTE_MINIMAL_DISTANCE.getKey(), minDistanceMeters);
        }
    }
```

**`private final double minDistanceMeters`** replaces `private static final double
MIN_DISTANCE_METERS = 500.0`. `static` had to go with the constant: the value is now
per-instance because it comes from config. The handler is `@Singleton`, so there is exactly one.

**`Config config` as the first constructor parameter.** Guice builds this class from the
`@Inject` constructor (`ProcessingHandler.java:90` calls `injector.getInstance`), and there is no
explicit provider for it in `MainModule` — verified by grep — so widening the constructor needs
no other edit. The only other caller is the stage 0 harness.

**The `LOGGER.info` line.** Remove it and there is no way to tell, from a running process, what
gate it is enforcing. That is not hypothetical: dating the July 2026 gate deploy took three
independent forensic methods (errata item 23) precisely because the value was a compiled-in
constant that announced nothing.

**The `minDistanceMeters <= 0` warning.** This is the fail-open alarm. Remove it and a
misconfiguration that quadruples Overpass load produces no signal at all until the upstream
service notices. It cannot be an exception: refusing to start would turn a load problem into an
outage.

**Alternative rejected — declaring the key without a default.** `Config.getInteger` ends with
`Objects.requireNonNullElse(defaultValue, 0)` (`Config.java:104-112`), so a two-argument
`IntegerConfigKey` resolves to `0` when the key is unset, `distanceMoved < 0` is never true, and
the gate disappears. `TollGateConfigTest.gateKeyDeclaresAnExplicitFiveHundredMetreDefault`
resolves the key against an empty `Config` and asserts 500, so the trap is caught by the build
rather than by production.

*Answers: plan §1d, errata item 5 (the same `getInteger` trap in the opposite direction).*

---

### A3. `PositionInfoHandler.java:65-82` — the skip marker

```java
            double[] last = lastProcessedPositions.get(deviceId);
            if (last != null) {
                double distanceMoved = DistanceCalculator.distance(last[0], last[1], currentLat, currentLon);
                if (distanceMoved < minDistanceMeters) {
                    LOGGER.debug("Device {} moved only {} m - skipping external API calls", deviceId, distanceMoved);
                    position.set(KEY_TOLL_LOOKUP_SKIPPED, true);
                    callback.processed(false);
                    return;
                }
            }
            lastProcessedPositions.put(deviceId, new double[]{currentLat, currentLon});
```

**`position.set(KEY_TOLL_LOOKUP_SKIPPED, true)`** is the only added line. It states, on the
position itself, that no lookup was attempted.

**Remove it** and `readToll` falls back to its third clause, `!position.hasAttribute(KEY_TOLL)`,
which gives the same answer *today*. So removing it changes nothing observable right now — and
that is exactly why it has to be there. `CopyAttributesHandler.java:42` copies an attribute when
`last.hasAttribute(attribute) && !position.hasAttribute(attribute)`, which is precisely the
condition a gated-out position satisfies. Add `isToll` to `processing.copyAttributes` and every
skipped position inherits the previous reading, absence becomes impossible, and the window fills
with copies of one measurement. The fix reverts with no error and no log line. The marker is
already on the position, so `:42`'s own guard means the copy path cannot overwrite it.

The evidence that copying is off today is a field observation — 9 of 44 positions carrying the
attribute — not a config read, and the deployed `traccar.xml` fragment is known to be
incomplete. An invariant resting on an observation is not an invariant.

**Alternative rejected — inferring unknown from absence.** Cheaper by one boolean on three
positions in four (~26 bytes each; ~19 KB/day for a device logging 1,000 positions). It buys a
silent, undetectable regression path. Costed and rejected in plan §1a.

**Note on where this sits.** The `return` is *before* both provider calls, so `onFailure` never
runs on a gated-out position. A failure marker cannot substitute for a skip marker. The two are
not redundant.

*Answers: plan §1a "write the marker positively"; errata item 16's consequence 1.*

---

### A4. `PositionInfoHandler.java:151-154` — the failure marker

```java
                        @Override
                        public void onFailure(Throwable e) {
                            LOGGER.warn("Overpass query failed", e);
                            position.set(KEY_TOLL_LOOKUP_FAILED, true);
```

**Role.** A timeout is not a measurement. Before this line, a failed Overpass call wrote no
attribute at all, so the position was indistinguishable from a gated-out one *and* — through
`getBoolean` — from a confirmed `toll=no`. An Overpass outage mid-traversal read as "left the
toll road".

**Remove it** and `TollFieldReplayTest.overpassFailureMidTraversalDoesNotBreakTheRun` still
passes, because absence alone already yields `null` from `readToll`. What breaks is
diagnosability: there is then no way to tell a skip from a failure on a stored position, which
is the distinction needed to decide whether a quiet device is a gate problem or an upstream
problem. It also breaks the same way A3 does if `copyAttributes` is ever enabled.

**Symmetry check.** `regionLookupFailed` at `:110` does exactly this for the region provider and
is read at `RegionEventHandler.java:39`. This is the pattern, not an invention.

*Answers: plan §1a "three states are needed, not two".*

---

### A5. `TollEventHandler.java:78-85` — the tri-state read

```java
    private static Boolean readToll(Position position) {
        if (position.getBoolean(PositionInfoHandler.KEY_TOLL_LOOKUP_SKIPPED)
                || position.getBoolean(PositionInfoHandler.KEY_TOLL_LOOKUP_FAILED)
                || !position.hasAttribute(Position.KEY_TOLL)) {
            return null;
        }
        return position.getBoolean(Position.KEY_TOLL);
    }
```

This replaces `Boolean positionIsToll = position.getBoolean(Position.KEY_TOLL)` at the old
`:87`. It is the single read site for `isToll` in the backend; the frontend reads none.

**Clause order matters.** The skip marker is checked first and is authoritative "ahead of any
`isToll` value" — so that if a copy path ever does put a stale `isToll` onto a skipped position,
the marker still wins and the reading is still discarded. Reversing the order would make the
marker useless in exactly the scenario it exists for.

**The third clause** covers state this fix does not produce but old rows and other paths might:
an absent attribute with neither marker.

**Remove the whole method**, restoring `getBoolean`, and the field drive's seven consecutive
confirmations produce nothing, because `ExtendedModel.java:104-106` returns the primitive
`false` for an absent key. That is the defect.

**Alternative rejected — `getBoolean` plus a `false` check.** "If it reads `false`, look closer."
There is nothing to look at: by then the two cases are the same value. The information is
destroyed at `parseAsBoolean(attributes.get(key), false)`, not at the comparison.

**Alternative rejected — changing `parseAsBoolean`.** It backs every attribute in the system.
Not a schema migration; a read-site change.

**Side effect, intended.** `TollEventHandler.java:189`'s `positionIsToll != null` was dead: the
old line assigned an autoboxed primitive, which can never be null. It is now live, and gates
whether the state is persisted. Safe because a `null` reading means `addOnToll` returned without
touching the window or the marks, so there is nothing to persist. Noted here because it is the
kind of "dead guard becomes live" change that is invisible in review.

*Answers: plan §1a; errata items 5b and 22 (the `name`/`ref` fallbacks are per-element, which is
why the read site is the right layer).*

---

### A6. `TollRouteState.java:30` — `@JsonIgnoreProperties(ignoreUnknown = true)`

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public class TollRouteState {
```

**Role.** Rollback insurance, and it is in the 1a+1b commit rather than the 1c commit on purpose.

`TollEventHandler.java:50` builds a plain `new ObjectMapper()`, so
`FAIL_ON_UNKNOWN_PROPERTIES` is enabled. Once 1c writes `runStart`, `lastTrue` and `runValue`
into `toll:<deviceId>`, rolling the jar back to a class without those fields makes every read
throw — caught at `:128-130`, which logs a WARN and leaves `tollState` null, so every device
falls back to `fromDevice` and loses its window, with a warning *per position* until the keys are
rewritten.

**Remove it** and that is the rollback behaviour. **Move it into the 1c commit and it does
nothing**, because the annotation has to exist in the version being rolled back *to*. This is the
one line in the diff whose value depends entirely on which commit it is in.

`TollRouteStateWindowTest.stateToleratesUnknownPropertiesForRollback` deserialises a payload
carrying a field from a hypothetical later release and asserts it does not throw, so the ordering
constraint is enforced by the build rather than by the release notes.

*Answers: plan §1c "the rollback direction is worse than the roll-forward".*

---

### A7. `TollRouteState.java:174-222` — the window counts real evidence only

```java
    public void addOnToll(Boolean isToll, int duration) {
        addOnToll(isToll, duration, null);
    }

    public void addOnToll(Boolean isToll, int duration, Position position) {
        if (this.tollWindow == null) {
            this.tollWindow = new ArrayList<>();
        }

        if (isToll == null) {
            return;
        }

        if (position != null) {
            if (this.runValue == null || !this.runValue.equals(isToll)) {
                this.runValue = isToll;
                this.runStart = PositionMark.of(position);
            }
            if (isToll) {
                this.lastTrue = PositionMark.of(position);
            }
        }

        this.tollWindow.add(isToll);

        while (this.tollWindow.size() > duration) {
            this.tollWindow.remove(0);
        }
        ...
```

**`if (isToll == null) return;`** — the core of 1b. An unknown neither fills the window nor
resets it, so `duration` slots always mean `duration` real lookups.

**Remove it** and `null` is appended. `isOnToll` (`:225-243`) builds a `HashSet` and requires
`size() == 1`; a window containing `null` alongside anything else has size 2 or 3 and returns
`null` forever. No events before the change, none after — 1a alone is inert, which is why these
two are one commit.

**Alternative rejected — resetting the window on unknown.** Superficially "safe": forget what you
knew when you stop knowing. It makes confirmation unreachable for any device whose cadence is
faster than one fix per gate distance, which is the entire broken population — the window would
be cleared three times out of four and never reach six.

**`while` instead of `if`.** The trim was `if (size() > duration) remove(0)`, which removes at
most one entry per call. A window restored longer than `duration` grows by one and shrinks by one
on every position, so it never converges downward; `isOnToll`'s size test is an equality
(`:231`), so that device returns `null` forever.

**Remove the `while`,** restore the `if`, and lowering `event.tollRoute.minimalDuration` becomes
irreversible without flushing `toll:*` — which, since those keys have no TTL, means a manual
operation. This is the mechanism behind the brief's instruction not to lower `minimalDuration`,
and fixing it here does not make lowering it advisable, only survivable.
`anOverlongRestoredWindowConvergesDownward` loads a stored window of 10 with `duration = 6` and
asserts it converges on the first position.

**The two-argument overload** is kept and delegates. It has no production caller; it keeps the
window semantics testable without constructing a `Position`, and `TollRouteStateWindowTest` uses
it throughout.

**The `position != null` guard** is what makes that delegation safe: mark keeping is skipped
rather than NPE-ing.

**`runValue == null` counts as a new run.** This is the forward-compatibility fallback. State
restored from a payload written before these fields exist has `runValue == null`, so the first
reading after deploy opens a new run and `runStart` points at a real position rather than at a
Java default. Remove that clause and a restored state backdates to a `null` fix time and `0.0`
distance — the epoch, and zero metres. This is why no Redis flush is mandatory.

*Answers: plan §1b; errata item 6 (no TTL on `toll:<deviceId>`) and item 29.*

---

### A8. `TollRouteState.java:291-346` — `PositionMark`

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PositionMark {
        @JsonProperty private long positionId;
        @JsonProperty private Date deviceTime;
        @JsonProperty private Date fixTime;
        @JsonProperty private double totalDistance;

        public PositionMark() { }

        static PositionMark of(Position position) { ... }
        // getters and setters
    }
```

**Role.** A position reduced to the four values an event needs, so the state can remember a
traversal boundary without serialising a whole `Position` into Redis.

**Why both times.** `deviceTime` and `fixTime` look redundant — on the field export they are
equal on all 44 rows. They are not interchangeable to the consumers:
`Event(String, Position)` (`Event.java:25-30`) takes `getDeviceTime()` for `eventTime`, while
`stateStartToll` records `getFixTime()` as `tollrouteTime`. Storing one and reusing it for both
would silently change whichever it was not, on every device, in a commit about something else.
Remove `deviceTime` and `eventTime` starts carrying a fix time; remove `fixTime` and
`tollrouteTime` starts carrying a device time.

**`positionId`.** Re-points the event's `positionid` foreign key at the boundary position.
Sound because `DatabaseHandler` is the *last* position handler (`ProcessingHandler.java:89`) and
sets `position.setId(id)` at `DatabaseHandler.java:52` before its callback, so by the time any
event handler runs the position has a real row id. The mark is captured in an event handler, so
it always carries a persisted id.

**`totalDistance`** is read via `position.getDouble(KEY_TOTAL_DISTANCE)` rather than stored as a
reference, because it is what `tollStartDistance` and `tollDistance` are computed from.

**The no-arg constructor** exists for Jackson. Remove it and deserialisation throws, which
surfaces as the WARN-per-position path at `TollEventHandler.java:128-130`.

**Alternative rejected — nine flat fields on `TollRouteState`.** Same data, three times the
surface, and every one of them a new unknown property for a rolled-back jar to trip over. Two
nested objects and one `Boolean` is three new JSON keys instead of nine.

---

### A9. `TollRouteProcessor.java:36-43` — backdating the enter

```java
                if (startTollDist == 0) {   //entered toll
                    TollRouteState.PositionMark start = state.getRunStart();
                    boolean backdated = start != null && start.getFixTime() != null;
                    stateStartToll(state,
                            backdated ? start.getTotalDistance() : currentTotalDist,
                            backdated ? start.getFixTime() : position.getFixTime(),
                            tollRef, tollName);
                    checkEvent(state, position, 0, true, backdated ? start : null);
                }
```

**Role.** The traversal began where the run of confirmations began, not where the window closed.

**Concretely, on the field drive.** The run of `true` readings starts at position 14
(01:45:28, `totalDistance` 12,559,555 m) and the window closes at position 31 (01:46:46,
12,562,492 m). Without these lines the enter is recorded 2,936 m late — and position 31 is the
exit gantry, so the recorded entry lands where the vehicle left the road. The 407 ETR bills by
distance between entry and exit points, so this is the change that makes the pricing work
possible at all.

**`boolean backdated`** is computed once and used three times so the three values cannot disagree.
Remove it and inline the ternaries, and a state with `runStart` set but `getFixTime()` null could
take the mark's distance with the position's time.

**The `getFixTime() != null` half of the guard**, not just `start != null`: a `PositionMark`
deserialised from a payload that had the key but not the value is non-null with null fields.
Remove that half and `stateStartToll` receives a null `startTime`, `tollrouteTime` becomes null,
and `checkEvent`'s `state.getTollrouteTime() != null` guard (`:93`) swallows the event silently.

**`backdated ? start : null` as the last argument** rather than `start`: `checkEvent` treats a
non-null mark as "re-point the event", so passing an unusable mark would set `eventTime` to null.

*Answers: plan §1c; errata item 20 point 3 (the gate breaks enter confirmation more than exit
confirmation).*

---

### A10. `TollRouteProcessor.java:55-64` — backdating the exit

```java
                TollRouteState.PositionMark end = state.getLastTrue();
                boolean backdated = end != null && end.getFixTime() != null;
                double exitTotalDist = backdated ? end.getTotalDistance() : currentTotalDist;
                double currentTollDist = exitTotalDist - startTollDist;
                if (state.getTollExitDistance() == -1) { // good exit (enter notif was sent)
                    state.setTollExitDistance(exitTotalDist);
                    state.setTollrouteTime(backdated ? end.getFixTime() : position.getFixTime());
                    checkEvent(state, position, currentTollDist, false, backdated ? end : null);
```

**Role.** Mirror image of A9. The traversal ended at the last confirmation, not at the position
that completed the window of *non*-confirmations.

**`double currentTollDist = exitTotalDist - startTollDist`** moved above the `if`, and its input
changed from `currentTotalDist` to `exitTotalDist`. Leave it reading `currentTotalDist` and every
billed distance is inflated by `minimalDuration ×` the gate — about 3 km per traversal at the
shipped settings, on both ends, in opposite directions.

**`state.setTollExitDistance(exitTotalDist)`** keeps the device row consistent with the event.
Remove the change and the event says one distance while `tollExitDist` on the device says
another.

*Answers: plan §1c "record the exit at the last true reading".*

---

### A11. `TollRouteProcessor.java:91-116` — `checkEvent` states its type and stamps the schema

```java
    private static void checkEvent(TollRouteState state, Position position, double tollDist, boolean enter,
                                   TollRouteState.PositionMark mark) {
        if (state.getTollrouteTime() != null) {
            Event event;
            if (enter) {
                event = new Event(Event.TYPE_DEVICE_TOLLROUTE_ENTER, position);
                state.setTollExitDistance(-1);
            } else {
                event = new Event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, position);
                event.set(ATTRIBUTE_TOLL_DIST, tollDist);
            }

            if (mark != null) {
                event.setEventTime(mark.getDeviceTime());
                event.setPositionId(mark.getPositionId());
            }
            event.set(SCHEMA_ATTRIBUTE, SCHEMA_VERSION);
```

**`boolean enter` replaces `double tollStart`.** The old signature inferred the event type:
enter when `tollStart > 0`, exit when `tollStart == 0`. The enter call site passed
`currentTotalDist`, so the *type of event* was a function of the device's odometer. A device
confirming a toll road at `totalDistance == 0` emitted an exit where an enter belonged.

**Remove this change and 1c becomes unsafe**, which is why it shipped as its own commit
immediately before: A9 replaces the completing position's distance with the run start's, so an
inference on that value would start misfiring wherever a traversal began at odometer zero.

**On the evidence.** The brief still lists this as the leading candidate for the exit surplus,
"with a testable signature". It is falsified. I re-ran the query independently against the local
snapshot: **0 of 2,048 exits carry `tollDistance = 0`; 2,047 carry a positive value.** Errata
item 20 measures 20 of 118,184 fleet-wide and item 24 re-sizes the surplus at ~4,900 broken
pairs rather than 913. The mechanism is real and worth removing; it is not the cause.

**`Event event` is no longer initialised to `null`**, and the `if (event != null)` wrapper is
gone. Both branches now assign, so the guard was unreachable-false. Removing it changes nothing
observable — which by this document's own rule means it should not have been there.

**`event.setEventTime` / `setPositionId`.** The re-pointing itself. Remove them and `PositionMark`
is dead weight: the state would remember the boundary and then not use it.

**`event.set(SCHEMA_ATTRIBUTE, SCHEMA_VERSION)`.** Costs one integer on ~2,800 events a month and
is what makes the change reversible *in the record*. Remove it and a rollback leaves a band of
backdated events with nothing marking either edge, so reconciling the history means inferring
boundaries from the shape of the data — the exact forensic exercise errata item 23 had to perform
to date the July gate deploy from the event curve. It is the unstamped-jar problem one layer up,
with the same one-line fix.

*Answers: plan §1c "stamp the emitted event with a schema version"; errata items 17, 20, 24.*

---

### A12. `Keys.java:2058-2061` — `tollRoute.minimalDistance`

```java
public static final ConfigKey<Integer> TOLL_ROUTE_MINIMAL_DISTANCE = new IntegerConfigKey(
        "tollRoute.minimalDistance",
        List.of(KeyType.CONFIG),
        500);
```

**The third argument is the whole point.** Covered in A2; not repeated.

**Added, not renamed.** `setup/setup.sh` preserves `conf/traccar.xml` across every upgrade and
`Config.getInteger` returns `0` for an unset key rather than throwing, so renaming an existing
key would leave the old entry sitting unread with the feature silently dead — the same failure
shape as the defect being fixed. `TollGateConfigTest.existingTollKeysAreUntouched` asserts the
other three toll key names are unchanged.

**Naming precedent.** `geocoder.reuseDistance` (`Keys.java:1705-1707`, read at
`GeocoderHandler.java:41`) is the shape to copy but not the code: it gates on
`position.getDouble(KEY_DISTANCE)` — per-position path distance — while this gate uses haversine
displacement from the last lookup point. They diverge on curved routes and on duplicate positions.
`TollInvariantMatrixTest.gateMeasuresStraightLineDisplacementNotPathDistance` pins which one is
in force.

---

## Part B — the data flow, end to end

### B1. As it flows today (master)

| # | hop | file:line | value held | type | what a missing value means *here* |
|---|---|---|---|---|---|
| 1 | protocol decoder | `StartekProtocolDecoder` | no toll data at all | — | n/a — the device never reports toll |
| 2 | `ProcessingHandler` position chain | `ProcessingHandler.java:70-92` | position with decoder attributes | `Position` | — |
| 3 | `FilterHandler` | chain slot 7 | passes duplicates through | — | `filter.duplicate` has no default, so filtering is **off** |
| 4 | gate | `PositionInfoHandler.java:43-47` | ~3 of 4 positions return here | — | **nothing written.** "Skipped" is unrepresented |
| 5a | region callback (**thread boundary**) | `:53-82` | `country`/`state`/`city` | `String` | absent = not resolved; `regionLookupFailed` marks failure |
| 5b | toll callback (**thread boundary**) | `:84-121` | `isToll`, `tollRef`, `tollName`, `surface`, `highway` | `Boolean`/`String` | absent = **ambiguous**; failure writes nothing |
| 6 | attributes map | `ExtendedModel.attributes` | `LinkedHashMap`, **not thread-safe**, written by both callbacks | `Map<String,Object>` | — |
| 7 | Overpass response cache (**process boundary**) | `OverPassTollRouteProvider.java:50-67` | `CachedTollData` JSON, 3 dp key, 24 h TTL | JSON | miss = query; **a hit at a neighbouring point is indistinguishable from a real reading** |
| 8 | `CopyAttributesHandler` | `:42` | may fill absent attributes from the last position | — | would make absence unrepresentable |
| 9 | `DatabaseHandler` (**persistence boundary**) | `:50-52` | row written, `position.setId(id)` | `long` | — |
| 10 | `TollEventHandler` read | `:87` (old) | `position.getBoolean(KEY_TOLL)` | **`boolean`** | **absent → `false`.** The information is destroyed here |
| 11 | window | `TollRouteState.addOnToll:145-159` | last ≤ 6 readings | `List<Boolean>` | no null is possible; 3 in 4 entries are fabricated |
| 12 | decision | `isOnToll:163-179` | `HashSet` size must be 1 | `Boolean` | `null` = undecided |
| 13 | Redis state (**process boundary**) | `TollEventHandler.java:164-169` | `toll:<deviceId>`, **no TTL** | JSON | absent = rebuild from device row |
| 14 | `TollRouteProcessor` | `:15-52` | event built from the *completing* position | `Event` | — |
| 15 | device row (**persistence boundary**) | `:176-183` | `tollStartDistance`, `tollrouteTime` | `double`, `Date` | `0` doubles as "not in a traversal" |
| 16 | event row (**persistence boundary**) | `NotificationManager.updateEvent:86` | `tc_events` | row | — |
| 17 | frontend | `EventReportPage.jsx:435-455` | reads `tollName`, `tollRef`, `tollDistance` off events | — | never reads `isToll` |

**The defect, located.** "Absent" means three different things at hops 4, 5b and 10, and nothing
distinguishes them. At hop 4 it means *not asked*. At 5b it means *asked, failed*. At 10 it is
read as *asked, answered no*. On the field drive, hop 10 turns 35 unasked positions into 35
denials, and seven genuine confirmations never become six consecutive ones.

### B2. As it flows after the change

Hops 1-3, 6-9 and 15-17 are unchanged. The differences:

| # | hop | file:line | what changed |
|---|---|---|---|
| 4 | gate |  `PositionInfoHandler.java:65-82` | writes `tollLookupSkipped=true`. **"Skipped" is now a value, not an omission.** Threshold from `tollRoute.minimalDistance` |
| 5b | toll callback | `:151-154` | failure writes `tollLookupFailed=true` |
| 10 | read | `TollEventHandler.java:78-85`, called at `:114` | `readToll` returns `Boolean`: `TRUE`, `FALSE`, or **`null`**. Markers checked before the value |
| 11 | window | `TollRouteState.addOnToll:189-222` | `null` returns early — not appended, not reset. Six slots = six real lookups. Trim is `while` |
| 11b | marks | `:199-209` | `runStart` = first reading of the current run, `lastTrue` = latest confirmation |
| 13 | Redis state | same | payload gains `runStart`, `lastTrue`, `runValue`; class tolerates unknown properties |
| 14 | processor | `:36-43`, `:55-64`, `:91-116` | event built from the boundary mark; type stated not inferred; stamped `tollEventSchema=2` |

**The same field drive, traced.** Positions 1-5 are gated out and now carry `tollLookupSkipped`;
`readToll` returns `null`; the window stays empty. Position 6 is looked up, returns
`toll=no`, enters the window as `false`. Positions 7-13 skipped. Position 14 confirms —
`runValue` flips to `true`, `runStart` marks position 14 (01:45:28, 12,559,555 m). Positions
18, 22, 25, 28 confirm; the window is `[false, true × 5]` and undecided. Position 31 confirms;
the trim drops the `false`; the set has one member; `isOnToll` returns `true`;
`startTollDist == 0` so `stateStartToll` records **position 14's** distance and time, and
`checkEvent` emits one `deviceTollRouteEnter` carrying position 14's `deviceTime` and row id,
stamped schema 2. Position 35 confirms — `startTollDist > 0`, names only. Position 41 returns
`toll=no`; the window is mixed; undecided. **The capture ends there.**

No exit is emitted, and none can be: confirming one needs six consecutive non-toll lookups, about
3 km at a 500 m gate, and the capture ends 1,601 m past the gantry with exactly one non-toll
lookup in it. This corrects the brief, which asks the replay to assert one enter *and one exit*.

### B3. The three boundaries, and what still lives at each

- **Thread boundary** — `PositionInfoHandler.java:86` and `:117`. Two JAX-RS callbacks write into
  one `LinkedHashMap` from two threads with no synchronisation. This diff adds two more writes on
  that map (`tollLookupSkipped` at `:77` is on the calling thread and safe; `tollLookupFailed` at
  `:153` is **on a callback thread and is not**). It is no worse than the six writes already
  there, and it is not fixed here. Stage 3.
- **Process boundary** — Redis, twice. The response cache (`toll_route:` keys, 24 h TTL, 3 dp
  ≈ 137 m diagonal against a 20 m query) and the per-device state (`toll:` keys, **no TTL**).
  Neither is namespaced by environment. Untouched. Stages 2 and 3.
- **Persistence boundary** — position row, device row, event row. This diff changes what the
  event row holds (`eventtime`, `positionid`, `tollEventSchema`) and what the device row holds
  (`tollStartDistance` now the run start's). Both deliberate; both flagged in the 1c commit.

---

## Part C — what a future reader needs to not break it

**C1. A gated-out position carries `tollLookupSkipped`.**
Enforced at `PositionInfoHandler.java:77`, relied on at `TollEventHandler.java:79`.
*Violated by:* deleting the marker and trusting absence; adding `isToll` to
`processing.copyAttributes` (which the marker survives, but the *inference* would not); any new
handler between `PositionInfoHandler` and `TollEventHandler` that fills `isToll`.
*Symptom if violated:* the fleet goes quiet again, with no error and no log line.
*Guarded by:* `TollInvariantMatrixTest.skipMarkerIsPresentOnEveryGatedOutPosition`, which asserts
every position is either enriched or marked, never both and never neither.

**C2. The window contains only real lookups.**
Enforced by the `isToll == null` early return at `TollRouteState.java:194`.
*Violated by:* appending `null` (window never homogeneous, no events ever); resetting the window
on `null` (confirmation unreachable for any fast-reporting device); reading `isToll` anywhere
else with `getBoolean`.
*Guarded by:* `TollRouteStateWindowTest.unknownReadingsDoNotEnterTheWindow` and
`treatingUnknownAsFalseNeverConfirms`.

**C3. The window can shrink.**
`while`, not `if`, at `TollRouteState.java:214`.
*Violated by:* reverting to `if`. Then any stored window longer than `duration` is permanently
undecided, and lowering `event.tollRoute.minimalDuration` becomes irreversible without a manual
`toll:*` flush, since those keys have no TTL.
*Guarded by:* `anOverlongRestoredWindowConvergesDownward`.

**C4. The run-start mark outlives the window.**
`runStart` is updated only when the reading *changes* (`:203`), not on every append, so it can be
older than the six entries the window holds. That is deliberate: it is the boundary of the
traversal, not of the window.
*Violated by:* updating it on every append (the enter stops being backdated at all); clearing it
when the window trims (same); adding a `runStart` reset on `null` readings (the boundary would
jump to whichever position happened to follow a gap).
*Guarded by:* `TollFieldReplayTest.enterIsRecordedAtTheFirstConfirmingReadingNotTheSixth`, which
pins the enter to position 14 rather than 31.

**C5. `runValue == null` must keep meaning "new run".**
`TollRouteState.java:203`. This is the only thing that makes a Redis flush optional on deploy.
*Violated by:* initialising `runValue` to `false`. A restored pre-upgrade state would then treat
its first `false` reading as a continuation, and `runStart` would stay null while `backdated`
stayed false — recoverable, but the *first* traversal of every device after deploy would silently
take a different path than intended.

**C6. The event type is stated, never inferred.**
`TollRouteProcessor.java:91`. The `boolean enter` parameter must not be re-derived from a
distance.
*Violated by:* any refactor that reintroduces `tollStart > 0`. With backdating in place this now
misfires on traversals beginning at odometer zero, not just on devices at odometer zero.
*Guarded by:* `TollEventTypeTest.everyEventFromAnOdometerZeroTraversalIsAnEnter`.

**C7. `tollRoute.minimalDistance` must keep its explicit default.**
`Keys.java:2058-2061`. Dropping the third constructor argument silently disables the gate.
*Guarded by:* `TollGateConfigTest.gateKeyDeclaresAnExplicitFiveHundredMetreDefault`, which
resolves the key against an empty `Config`.

**C8. Not an invariant, an open defect — `tollStartDistance == 0` is both a legal odometer
reading and the "not in a traversal" sentinel.** `TollRouteProcessor.java:32,44,52`. A traversal
beginning at zero leaves the sentinel unset and re-arms the enter branch on every subsequent
position. C6 fixed the *type* of the resulting events; the *count* is still wrong. Fixing it needs
an explicit in-traversal flag, and `tollStartDistance` round-trips through the device row via
`TollRouteState.fromDevice`/`toDevice`, so it is a persisted-state change with its own rollback
story. Deliberately out of stage 1.

---

## Lines I could not justify

Two, both pre-existing and both left alone because removing them is out of scope for these
commits — recorded here rather than given an invented reason:

- **`TollRouteState.java:15`**, `import static org.traccar.handler.events.TollEventHandler.LOGGER`,
  is shadowed by the class's own `LOGGER` field on the next line. It has no effect. It also makes
  a state object import a handler, which is the wrong direction.
- **`TollEventHandler.java:104`**, `if (device != null && device.hasAttribute(...))`. `device` was
  null-checked and returned on at `:92`, so the first half is always true.

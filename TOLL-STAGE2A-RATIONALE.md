# TOLL-STAGE2A-RATIONALE.md — the safety rails, line by line

Written from the diff `riq-toll-stage1-fix..riq-toll-stage2a`, using post-change line numbers.
Companion to `TOLL-FIX-RATIONALE.md`, which covers stage 1. Same discipline: this describes what
is in the tree, not what was intended.

Five commits:

| commit | contents |
|---|---|
| `66bb280` | bounded enrichment client, Overpass query budgets, queue bound, evictions, deferred 1d half |
| `356bd9e` | drop accounting, pool resized on measured peak, service-time instrumentation |
| `cf93325` | geocoder onto its own bounded client, both pools resized |
| `64838ce` | startup smoke test for the Guice wiring |
| `558ac26` | extrapolated sizing replaced with production-measured concurrency |

Files: `EnrichmentClient.java` (+28), `GeocoderClient.java` (+27), `MainModule.java` (+98),
`ProcessingHandler.java` (+78), `Keys.java` (+138), `GeocoderHandler.java` (+38),
`PositionInfoHandler.java` (+83), the two Overpass providers (+15),
`BoundedClientWiringTest.java` (+148), `FieldDrive.java` (+40).
608 tests green, `checkstyleMain` clean.

**What this PR is for.** Stage 1 made toll events fire again and made the gate lowerable. Both
raise enrichment volume. Before stage 1, the 500 m gate was the *only* thing bounding external
call volume — there were no client timeouts, no executor cap, no queue bound, and no eviction
anywhere on the path. 2a is what makes stage 1 safe to deploy, which is why the two ship together.

---

## Part A — every changed hunk, justified

### A1. `EnrichmentClient.java`, `GeocoderClient.java` — two Guice binding annotations

**Role.** Marks the two clients used from inside the position handler chain, so they can be bound
separately from the one every other subsystem shares.

**Why two annotations and not one, or none.** The shared `Client` from `MainModule.java:171` is
injected into ~35 classes across eight subsystems: SMS, three forwarders, ~20 geocoders via
`JsonGeocoder`, four geolocation providers, three notificators, `StatisticsManager`,
`TaskHealthCheck`, two VIN decoders, and the enrichment providers. A read timeout that suits
Overpass does not suit an SMS gateway, and there is no basis for choosing one on its behalf.
Marking the hot-path callers instead means every other subsystem's behaviour is byte-for-byte
unchanged.

Two rather than one because the enrichment providers call Overpass and the geocoder calls
LocationIQ. Sharing a pool would couple their failure modes — a LocationIQ stall would consume
workers toll detection needs, for reasons unrelated to Overpass.

**Remove them** and the retargeted `@Provides` methods fall back to the shared client, silently.
Nothing fails at compile time. The bounds still exist but nothing uses them.

**Alternative rejected — putting timeouts on the shared client.** One line instead of two
annotations, and it changes the behaviour of SMS delivery, event forwarding, geocoding,
geolocation, notifications, statistics, the health check and VIN decoding at the same time. The
blast radius is the argument.

---

### A2. `MainModule.java:193-217` — `boundedClient`

```java
    private static Client boundedClient(
            String name, ObjectMapperContextResolver objectMapperContextResolver,
            int connectTimeout, int readTimeout, int maxConcurrent, int queueSize) {

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                maxConcurrent, maxConcurrent, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize),
                runnable -> { ... daemon thread named `name` ... },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        ...
        return ClientBuilder.newBuilder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .executorService(executor)
                .build()
                .register(objectMapperContextResolver);
    }
```

Three bounds, and they interlock. Each one alone is insufficient:

- **The timeouts** stop a single hung request occupying a worker forever. Without them the pool
  cap below is meaningless — a bounded pool whose workers never return is a pool of zero.
- **`.executorService(executor)`** replaces Jersey's default. `DefaultClientAsyncExecutorProvider`
  sizes from `ClientProperties.ASYNC_THREADPOOL_SIZE`, which defaults to `0` — an unbounded cached
  pool, one platform thread per in-flight request. Verified from the bytecode of
  `jersey-client-3.1.10`, not from documentation. This is the line that turns "a slow upstream
  becomes a thread leak" into "a slow upstream becomes rejections".
- **`ArrayBlockingQueue` + `AbortPolicy`** is the rejection path. A `LinkedBlockingQueue` — the
  obvious choice, and what Jersey's own `ThreadPoolExecutorProvider` uses — is unbounded, so work
  beyond the workers accumulates in memory instead of being refused. `AbortPolicy` throws
  `RejectedExecutionException`, which surfaces through the provider's `onFailure` and, after stage
  1, reads as *unknown* rather than *not tolled*.

**`allowCoreThreadTimeOut(true)`** with equal core and max sizes means the pool idles down to zero
threads between traffic. Remove it and 768 threads sit allocated permanently.

**Alternative rejected — `CallerRunsPolicy`.** The usual choice for backpressure, and wrong here:
the caller is the handler-chain thread for that device, so running the request on it would block
the chain — converting a bounded queue back into an unbounded stall, which is the thing being
fixed.

*Answers: brief PR 2a bullets 1 and 2.*

---

### A3. `MainModule.java:228-253` — the two providers, and `:307`, `:364`, `:379`, `:417`

Four one-line signature changes retarget the hot-path consumers:

| line | provider | now takes |
|---|---|---|
| `:307` | `provideGeocoder` | `@GeocoderClient Client` |
| `:364` | `provideSpeedLimitProvider` | `@EnrichmentClient Client` |
| `:379` | `provideTollRouteProvider` | `@EnrichmentClient Client` |
| `:417` | `provideRegionProvider` | `@EnrichmentClient Client` |

`:307` is the one that matters most and was missed in the first cut. `provideGeocoder` hands the
same `client` to all ~20 geocoder implementations, so one parameter change covers every type.
`GeocoderHandler` sits in the chain four slots ahead of `PositionInfoHandler`, and its
`geocoder.reuseDistance=200` gate is on **per-position** distance rather than distance from the
last lookup — so on any route whose steps exceed 200 m it fires close to once per position. The
Illinois fixture's median step is 221 m. Leaving it on the unbounded shared client meant 2a
converted an unbounded queue into lost telemetry without removing the cause.

**Remove any one of these four** and that provider silently reverts to the shared unbounded
client. This is exactly the failure `BoundedClientWiringTest` exists to catch, and it is why that
test asserts the three clients are *distinct instances* rather than merely non-null.

---

### A4. `OverPassTollRouteProvider.java:41-47`, `OverpassSpeedLimitProvider.java:36-41` — `[timeout:N]`

```java
        int queryTimeout = config.getInteger(Keys.ENRICHMENT_OVERPASS_QUERY_TIMEOUT);
        this.url = baseurl + "?data=[out:json][timeout:" + queryTimeout + "];" + ...
```

**Role.** The server's own query budget, distinct from the client read timeout.

**Why both.** The read timeout frees *our* worker; `[timeout:N]` lets Overpass abandon the query
and free *its* resources. With only the client timeout, a slow query keeps running upstream after
we have stopped waiting for it — so the instance we are already overloading stays overloaded.

**The default is 10 s against a 15 s read timeout, and the ordering is the point.** The server must
give up first, so we get a clean error response rather than a socket timeout with work still
running remotely. `BoundedClientWiringTest.overpassQueryBudgetIsBelowTheReadTimeout` pins that
relationship, because it is the kind of thing a later config edit breaks silently.

---

### A5. `PositionInfoHandler.java:98-107` — `recordLookupPoint`, and the deferred half of 1d

```java
    private void recordLookupPoint(long deviceId, double latitude, double longitude) {
        if (lastProcessedPositions.size() >= MAX_TRACKED_DEVICES
                && !lastProcessedPositions.containsKey(deviceId)) {
            LOGGER.warn("Gate reference map reached {} devices - clearing. ...", MAX_TRACKED_DEVICES);
            lastProcessedPositions.clear();
        }
        lastProcessedPositions.put(deviceId, new double[]{latitude, longitude});
    }
```

Called from the toll provider's `onSuccess` (`:200`), no longer before the provider calls.

**What changed and why it waited.** The gate's reference point now advances on *success* rather
than on *attempt*, so a failed lookup no longer costs a whole gate distance — the next position
retries. That is a conditional loosening of the gate that fires precisely when the upstream is
already failing, which is why stage 1 explicitly held it back. It is safe only because A2 exists:
the ceiling is now on concurrency and duration, not on how often a position asks. At the 15 s read
timeout, 512 workers let ~34 req/s reach a failing service against ~136 offered; the rest is
rejected at the queue.

**Remove the `size()` check** and the map grows with every device ever seen. Eviction is crude on
purpose — clear-all rather than LRU — because the entry is a 16-byte coordinate pair whose only
cost of loss is one extra lookup for that device. Tracking access order to protect that is not
worth the allocation.

**Alternative rejected — advancing the reference on failure by a smaller amount.** Bounds the
retry rate without needing the pool cap, and adds a second tuning knob whose correct value depends
on the same unmeasured service time. The pool cap already bounds it, in a way that does not need
tuning.

---

### A6. `PositionInfoHandler.java:116-131`, `GeocoderHandler.java:63-77` — service-time instrumentation

```java
    private void recordServiceTime(long startNanos, boolean failed) {
        long elapsed = System.nanoTime() - startNanos;
        long count = lookupCount.incrementAndGet();
        ...
        if (count % SERVICE_TIME_SAMPLE_INTERVAL == 0) {
            LOGGER.info("Enrichment service time over {} lookups: mean {} ms, max {} ms ...", ...);
        }
    }
```

**Role.** Makes the one input the pool sizing cannot derive from the fleet observable.

Concurrency = throughput × service time. Throughput is now measured (Part B). Service time is
assumed at ~0.3 s and nothing in the fleet data implies it. Without these lines both pool
defaults are unfalsifiable guesses; with them, the first 500 lookups after deploy settle it.

**Mean and max, not percentiles.** The sizing needs a scale, not a distribution — enough to tell
0.3 s from 0.9 s. A histogram on the hot path costs more than the decision is worth.

**`lookupNanosMax.getAndSet(0)`** resets per summary, so the max is "since last summary" rather
than all-time. An all-time max is dominated by the first timeout that ever occurs and then never
moves again.

**Remove them** and nothing fails. The pools keep working at whatever size they were set to, and
the only way to discover they are wrong is a user noticing enrichment coverage has degraded —
which is the silent-failure shape this whole workstream exists to remove.

---

### A7. `ProcessingHandler.java:178-204` — the per-device queue bound

```java
            if (queueMaxSize > 0 && queue.size() >= queueMaxSize) {
                dropped = true;
            } else {
                queue.offer(position);
            }
        }
        if (dropped) {
            long deviceTotal = droppedByDevice.computeIfAbsent(...).incrementAndGet();
            long total = droppedTotal.incrementAndGet();
            if (deviceTotal == 1 || deviceTotal % DROP_LOG_INTERVAL == 0) {
                LOGGER.warn("Device {} queue is at its {} limit - DROPPING position at {} "
                        + "(never stored). {} dropped for this device, {} process-wide. ...", ...);
            }
```

**Drop-newest, not drop-oldest.** Dropping the oldest would reorder a stream that
`PositionUtil.isLatest` and every windowed detector assume is monotonic in `fixTime`. A reordered
stream is a correctness failure; a truncated one is a resolution failure.

**A dropped position is never stored.** `DatabaseHandler` is the *last* position handler
(`ProcessingHandler.java:131`), so a position that never enters the chain never reaches it. This is
real telemetry loss and the comment says so in those words.

**What it does not break: odometer continuity.** `PostProcessHandler.java:58` is what calls
`cacheManager.updatePosition`, and it runs only for positions that complete the chain — so a
dropped P2 leaves P1 cached, and P3's `DistanceHandler` computes `distance(P1,P3)` with
`totalDistance = P1.total + that`. The odometer stays continuous.

**Why the shed position is not persisted unenriched instead.** The queue is full *because* the
per-device chain is occupied by an earlier position. Processing the shed one concurrently would put
two positions for one device through `DistanceHandler` and `PostProcessHandler` at once, racing
`cacheManager.getPosition`/`updatePosition` and breaking exactly the serialisation the drop-newest
choice above is protecting. Storing it bare, bypassing the chain, is worse than dropping: the row
would carry no `totalDistance`, and `PositionUtil.calculateDistance:44-55` *differences*
`KEY_TOTAL_DISTANCE` between two rows, so any report whose boundary landed there returns nonsense.
A missing row is recoverable; a row with a false odometer is not.

**The accounting is the mitigation.** A bound that sheds invisibly is only a better failure than an
`OutOfMemoryError` if someone finds out. Hence a process total, a per-device count, both on every
line, and a recovery WARN at `:275-279` when a device's queue drains reporting what it lost. The
`DROP_LOG_INTERVAL` rate limit bounds log volume under a sustained stall while every drop is still
counted.

**`queueMaxSize > 0`** makes a zero or unset key disable the bound rather than drop everything —
fail-open for a *bound*, which is the correct direction here and the opposite of the gate key's.

---

### A8. `ProcessingHandler.java:95-100` — `discardQueueIfEmpty`

`processNextPosition` already removed the device from the cache when its queue drained, but left
the `LinkedList` in `queues`. The map therefore grew by one entry per device ever seen and never
shrank. Removing this method restores that leak; it changes nothing else.

---

### A9. `Keys.java:2069-2200` — eleven config keys, every one with an explicit default

`enrichment.client.{connectTimeout,readTimeout,maxConcurrent,queueSize}`,
`enrichment.overpass.queryTimeout`, `geocoder.client.{connectTimeout,readTimeout,maxConcurrent,queueSize}`,
`processing.queue.maxSize`.

**The explicit defaults are load-bearing, not stylistic.** `Config.getInteger` ends with
`Objects.requireNonNullElse(defaultValue, 0)`, so a two-argument `IntegerConfigKey` resolves to `0`
for an unset key. For every key here, `0` disables the bound it represents. This is the same trap
as `event.tollRoute.minimalDuration` and `tollRoute.minimalDistance`, and
`BoundedClientWiringTest.everyBoundHasAnExplicitNonZeroDefault` resolves all eleven against an
empty `Config` so the build catches it.

---

## Part B — the load arithmetic, and where each number comes from

The sizing rests on four numbers. Three are measured; one is not, and that is the whole reason
A6 exists.

| input | value | source |
|---|---|---|
| devices with toll state | ~2,762 | production event query, errata item 16 |
| peak concurrent moving | **354** (12.8 %) | **production**, 1-minute buckets, `speed > 2` |
| requests per moving device | 0.385 enrichment, ~0.256 geocoder | derived from 3.9 s cadence and the gates |
| service time | **~0.3 s, assumed** | **not measured** — A6 makes it observable |

**The peak-concurrency number was wrong once and it matters.** An earlier revision used 739, from
53 of 198 devices in a local snapshot holding 1.7 % of production's events, scaled 14×. The
measured figure is less than half that. The local snapshot over-represents active devices roughly
2×, so concurrency ratios must not be scaled from it — recorded in the `Keys` javadoc as a
correction rather than silently overwritten, because the pool sizes were chosen against the wrong
number and deliberately left alone afterwards.

Offered load at measured peak: **~136 req/s** enrichment, **~91 req/s** geocoder.

| service time | enrichment concurrency needed | geocoder |
|---|---|---|
| 0.2 s | 27 | 18 |
| 0.3 s | 41 | 27 |
| 0.5 s | 68 | 46 |
| 1.0 s | 136 | 91 |
| 2.0 s | 273 | 182 |

Shipped: **512** enrichment (covers ~3.8 s), **256** geocoder (covers ~2.8 s). Both substantially
over-provisioned. Left that way on purpose until the A6 summaries land, because the two failure
directions are not symmetric — idle threads blocked on IO cost reserved stack and nothing else,
while under-provisioning surfaces as queue rejections that read as *unknown* and degrade
enrichment coverage silently.

The enrichment pool has been raised twice: 128 was short above ~0.45 s against the old estimate,
256 above ~0.9 s. That history is the argument for starting high and tuning down from logs rather
than iterating upward from production incidents.

**Outage behaviour.** At the 15 s read timeout the pools let ~34 and ~17 req/s reach the network —
roughly a quarter and a fifth of what is offered — and reject the rest at the queue. Rejections
surface as lookup failures, which stage 1's tri-state reads as unknown, so the confirmation window
is untouched. An Overpass outage degrades coverage; it does not corrupt state or stampede.

---

## Part C — what a future reader needs to not break it

**C1. The hot path uses bounded clients; everything else uses the shared one.**
Enforced by the four annotated parameters at `MainModule.java:307,364,379,417`.
*Violated by:* removing an annotation (silent fallback to unbounded), or adding a new
position-chain handler that takes a bare `Client`.
*Guarded by:* `BoundedClientWiringTest.allThreeClientsResolveAndAreDistinct`, which asserts three
*distinct* instances.

**C2. The two bounded pools stay separate.**
*Violated by:* consolidating them to save threads. A LocationIQ stall would then consume Overpass
workers and take toll detection down with the geocoder.
*Guarded by:* the same test's third `assertNotSame`.

**C3. The Overpass query budget stays below the client read timeout.**
`enrichment.overpass.queryTimeout` (10 s) < `enrichment.client.readTimeout` (15 s).
*Violated by:* raising the former or lowering the latter in config. The server would then still be
running a query we have abandoned.
*Guarded by:* `overpassQueryBudgetIsBelowTheReadTimeout`.

**C4. Every bound has a non-zero explicit default.**
*Violated by:* declaring a new bound with the two-argument `IntegerConfigKey`, which resolves to 0
and disables it.
*Guarded by:* `everyBoundHasAnExplicitNonZeroDefault`.

**C5. The gate's reference point advances only on success.**
`PositionInfoHandler.java:200`. This is only safe while A2's pool cap exists.
*Violated by:* moving the `recordLookupPoint` call back before the provider calls (restores the
lost-budget-on-failure behaviour), or removing the pool cap while leaving it (restores the
retry-amplification risk stage 1 refused).

**C6. Drop-newest, never drop-oldest.**
`ProcessingHandler.java:182-186`.
*Violated by:* switching to drop-oldest to "keep the freshest data". That reorders the stream and
breaks `PositionUtil.isLatest` and every windowed detector.

**C7. Every drop is counted.**
*Violated by:* removing the counters, or rate-limiting the count rather than only the log line.
The bound is defensible only because it is loud.

---

## Outstanding — what is not done

1. **No test covers the drop policy, the `AbortPolicy` rejection path, or timeout behaviour.**
   531 lines of main source, and the untested part is the one that loses telemetry rather than
   degrading gracefully. Accepted knowingly for the first deploy: at 354 concurrent devices against
   pools sized for ~950 the queue should never fill, the drop path is an overload valve nowhere
   near overload, and it announces itself. **The first week's WARN count is the test.** A clean log
   means the path never executed; drops at 12.8 % concurrency would mean something is wrong that no
   unit test would have predicted. Add the test after, not before.

2. **Service time is still assumed.** Both pool sizes should be revisited against the first A6
   summaries. Near 0.3 s and both are ~12× over-provisioned and should come down; near 1 s and 512
   is still comfortable but the geocoder's 256 gets tighter.

3. **`tollRoute.minimalDistance` stays at 500** through this deploy and stage 1's. Lowering it is
   the next decision and belongs after the service-time numbers land.
   `TollFacilitySeparationTest` already encodes what lowering it buys.

4. **PR 2b — the dual store — is not started.** Its prerequisite could not be run: the log grep for
   `RedisCache`'s WARN strings needs production logs, which were not available. A clean log would
   kill the hypothesis; an unavailable one leaves it untested, and those are not the same. Prior art
   exists in `rides-iq-release-4-be-fixes-2026-07-07.md`, which drafts a `RedisCache` refactor
   returning booleans.

5. **Deploy prerequisite, pre-existing and not from this PR:** the `logs/` directory must exist on
   the host. `Config`'s constructor calls `Log.setupLogger` (`Config.java:51`) and `Config` is an
   eager singleton, so a `RollingFileHandler` for `./logs/tracker-server.log` is installed during
   injector creation on every version; it opens the file on first write, which in production is
   `Main.run`'s own `logSystemInfo()`. A missing directory kills startup with or without 2a.

6. **Never run.** Everything here is compile-, test- and injector-level verification. Nobody has
   started this server with these changes.

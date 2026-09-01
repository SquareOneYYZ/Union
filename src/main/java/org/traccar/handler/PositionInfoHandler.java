package org.traccar.handler;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.model.Position;
import org.traccar.tollroute.TollData;
import org.traccar.tollroute.TollRouteProvider;
import org.traccar.tollroute.RegionData;
import org.traccar.tollroute.RegionProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
public class PositionInfoHandler extends BasePositionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PositionInfoHandler.class);
    private final TollRouteProvider tollRouteProvider;
    private final RegionProvider regionProvider;

    /**
     * Set on a position whose enrichment lookup the distance gate skipped. Distinguishes
     * "not looked up" from "looked up and not on a toll road", which are otherwise identical
     * once {@code getBoolean} has turned an absent key into {@code false}.
     */
    public static final String KEY_TOLL_LOOKUP_SKIPPED = "tollLookupSkipped";

    /**
     * Set when the Overpass lookup itself failed. Mirrors {@code regionLookupFailed} at
     * {@code :77}. A timeout must not read as "left the toll road".
     */
    public static final String KEY_TOLL_LOOKUP_FAILED = "tollLookupFailed";

    /**
     * Ceiling on tracked devices. Production has ~2,762 devices with toll state; 10,000 leaves
     * headroom while keeping the map bounded at roughly 0.5 MB.
     */
    private static final int MAX_TRACKED_DEVICES = 10000;

    /**
     * How many completed lookups between service-time summaries. The pool that serves these calls
     * is sized by Little's Law - concurrency = throughput x service time - and service time is the
     * one input in that arithmetic that cannot be derived from the fleet. It is currently assumed
     * to be ~0.3 s; 256 workers cover up to ~0.9 s at measured peak concurrency, and are short
     * above that. This makes the real number observable so the default can be checked rather than
     * trusted. See {@code Keys.ENRICHMENT_MAX_CONCURRENT}.
     */
    private static final int SERVICE_TIME_SAMPLE_INTERVAL = 500;

    private final double minDistanceMeters;
    private final ConcurrentHashMap<Long, double[]> lastProcessedPositions = new ConcurrentHashMap<>();

    private final AtomicLong lookupCount = new AtomicLong();
    private final AtomicLong lookupNanosTotal = new AtomicLong();
    private final AtomicLong lookupNanosMax = new AtomicLong();
    private final AtomicLong lookupFailures = new AtomicLong();

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

    /**
     * Advances the gate's reference point, and bounds the map that holds it.
     *
     * <p><b>Called on success only.</b> Previously the reference advanced before the provider
     * calls, so a failed lookup consumed the budget and the next attempt was a whole gate distance
     * further on. Now a failure leaves the reference where it was and the next position retries.
     *
     * <p>That is a conditional loosening of the gate, and it triggers precisely when the upstream
     * is already failing - which is why it was held out of stage 1 and belongs here. It is safe
     * only because the enrichment client is now bounded: at most
     * {@code enrichment.client.maxConcurrent} requests in flight, each for at most
     * {@code enrichment.client.readTimeout}, with everything beyond the queue rejected outright.
     * Retries can no longer become a stampede, because the ceiling is on concurrency and duration
     * rather than on how often a position asks.
     *
     * <p>The map is bounded here rather than left to grow with every device ever seen. Eviction is
     * crude on purpose: the entry is a 16-byte coordinate pair whose only cost of loss is one
     * extra lookup for that device, so a size check beats tracking access order.
     */
    private void recordLookupPoint(long deviceId, double latitude, double longitude) {
        if (lastProcessedPositions.size() >= MAX_TRACKED_DEVICES
                && !lastProcessedPositions.containsKey(deviceId)) {
            LOGGER.warn("Gate reference map reached {} devices - clearing. Each affected device "
                    + "makes one extra enrichment lookup on its next position", MAX_TRACKED_DEVICES);
            lastProcessedPositions.clear();
        }
        lastProcessedPositions.put(deviceId, new double[]{latitude, longitude});
    }

    /**
     * Records one completed enrichment lookup, successful or not, and emits a summary every
     * {@link #SERVICE_TIME_SAMPLE_INTERVAL} completions.
     *
     * <p>Mean and max rather than percentiles on purpose: the pool sizing needs a service-time
     * scale, not a distribution, and a mean plus a max is enough to tell 0.3 s from 0.9 s without
     * carrying a histogram on the hot path.
     */
    private void recordServiceTime(long startNanos, boolean failed) {
        long elapsed = System.nanoTime() - startNanos;
        long count = lookupCount.incrementAndGet();
        lookupNanosTotal.addAndGet(elapsed);
        lookupNanosMax.accumulateAndGet(elapsed, Math::max);
        if (failed) {
            lookupFailures.incrementAndGet();
        }
        if (count % SERVICE_TIME_SAMPLE_INTERVAL == 0) {
            long meanMillis = lookupNanosTotal.get() / count / 1_000_000L;
            long maxMillis = lookupNanosMax.getAndSet(0) / 1_000_000L;
            LOGGER.info("Enrichment service time over {} lookups: mean {} ms, max {} ms since last "
                            + "summary, {} failures. Pool is sized for ~{} ms - see "
                            + "enrichment.client.maxConcurrent",
                    count, meanMillis, maxMillis, lookupFailures.get(), 300);
        }
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (position.getValid()) {

            double currentLat = position.getLatitude();
            double currentLon = position.getLongitude();
            long deviceId = position.getDeviceId();

            double[] last = lastProcessedPositions.get(deviceId);
            if (last != null) {
                double distanceMoved = DistanceCalculator.distance(last[0], last[1], currentLat, currentLon);
                if (distanceMoved < minDistanceMeters) {
                    LOGGER.debug("Device {} moved only {} m - skipping external API calls", deviceId, distanceMoved);
                    // Positive marker, not inferred absence. TollEventHandler treats this as
                    // "not looked up", which is neither a confirmation nor a contradiction.
                    // Written rather than inferred because CopyAttributesHandler:42 copies an
                    // attribute exactly when the position lacks it - so if isToll were ever
                    // added to processing.copyAttributes, absence would stop meaning unknown
                    // and the defect would return silently. The marker is already present, so
                    // that copy path cannot overwrite it.
                    position.set(KEY_TOLL_LOOKUP_SKIPPED, true);
                    callback.processed(false);
                    return;
                }
            }
            // The reference point advances on SUCCESS, not on attempt - see recordLookupPoint.
            // Use atomic counter to track both async callbacks
            AtomicInteger pendingCallbacks = new AtomicInteger(2);
            long lookupStartNanos = System.nanoTime();

            regionProvider.getRegion(currentLat, currentLon,
                    new RegionProvider.RegionProviderCallback() {
                        @Override
                        public void onSuccess(RegionData data) {
                            if (data.getCountry() != null) {
                                position.set(Position.KEY_COUNTRY, data.getCountry());
                                LOGGER.info("Setting country: {}", data.getCountry());
                            }
                            if (data.getState() != null) {
                                position.set(Position.KEY_STATE, data.getState());
                                LOGGER.info("Setting state: {}", data.getState());
                            }
                            if (data.getCity() != null) {
                                position.set(Position.KEY_CITY, data.getCity());
                                LOGGER.info("Setting city: {}", data.getCity());
                            }
                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }

                        @Override
                        public void onFailure(Throwable e) {
                            LOGGER.warn("LocationIQ region query failed", e);
                            position.set("regionLookupFailed", true);
                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }
                    });

            tollRouteProvider.getTollRoute(currentLat, currentLon,
                    new TollRouteProvider.TollRouteProviderCallback() {
                        @Override
                        public void onSuccess(TollData data) {
                            recordServiceTime(lookupStartNanos, false);
                            recordLookupPoint(deviceId, currentLat, currentLon);
                            if (data.getToll() != null) {
                                position.set(Position.KEY_TOLL, data.getToll());
                            }
                            if (data.getRef() != null) {
                                position.set(Position.KEY_TOLL_REF, data.getRef());
                            }
                            if (data.getName() != null) {
                                position.set(Position.KEY_TOLL_NAME, data.getName());
                            }
                            if (data.getSurface() != null) {
                                position.set(Position.KEY_SURFACE, data.getSurface());
                            }
                            if (data.getHighway() != null) {
                                position.set(Position.KEY_HIGHWAY, data.getHighway());
                                LOGGER.info("Setting highway: {}", data.getHighway());
                            }
                            if (data.getEnforcement() != null) {
                                position.set(Position.KEY_ENFORCEMENT, data.getEnforcement());
                                LOGGER.info("Setting enforcement: {}", data.getEnforcement());
                            }

                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }

                        @Override
                        public void onFailure(Throwable e) {
                            recordServiceTime(lookupStartNanos, true);
                            LOGGER.warn("Overpass query failed", e);
                            // A failed lookup is not a reading. Without this the position
                            // carries no isToll and is indistinguishable from a gated-out one,
                            // and on master it was indistinguishable from toll=no as well.
                            position.set(KEY_TOLL_LOOKUP_FAILED, true);
                            if (pendingCallbacks.decrementAndGet() == 0) {
                                callback.processed(false);
                            }
                        }
                    });
        } else {
            callback.processed(false);
        }
    }
}

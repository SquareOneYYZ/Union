package org.traccar.handler.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.state.SpeedCameraState;
import org.traccar.storage.localCache.RedisCache;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Singleton
public class SpeedCameraEventHandler extends BaseEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedCameraEventHandler.class);

    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private final Config config;
    private final int confidenceWindow = 1; // could be configurable
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    /**
     * Per-device state only carries the 60 s re-emission lock, so an hour is ample; without a TTL
     * one key per device ever seen stayed in Redis forever (plan D7).
     */
    static final int STATE_TTL_SECONDS = 3600;

    /**
     * Camera-zone counters since startup (plan D2, section 4.3). They reset on restart, so the INFO
     * summary below reads as a per-deploy figure; the getters are for tests.
     */
    private final AtomicLong cameraZoneReads = new AtomicLong();
    private final AtomicLong readNoLimit = new AtomicLong();
    private final AtomicLong overLimit = new AtomicLong();

    /**
     * Camera-zone positions between INFO summaries of the counters. Pre-fix prod fired about 460
     * events a day after the 60 s lock, so camera-zone reads are in the low thousands a day and this
     * is a few dozen lines a day at most.
     */
    static final long COUNTER_LOG_INTERVAL = 100;

    /**
     * Equality guard, not a tolerance. At runtime a vehicle at exactly the limit compares equal: the
     * decoders and OverpassSpeedLimitProvider both convert km/h with UnitsConverter.knotsFromKph, so a
     * strict compare would not fire and the guard only absorbs ulp-level noise. It exists for the
     * offline replay (plan 7.2): tc_positions.speed is a FLOAT column, so an exported 50 km/h reads
     * 26.9978504 against a double limit of 26.99785, and 539 of 42,210 exported rows sit there. The
     * smallest genuine over-limit reading was 0.26 kn; 0.01 kn (0.02 km/h) sits far from both and is
     * negligible under the buffer. Operator tolerances are stage B's calendar.
     */
    static final double SPEED_EQUALITY_EPSILON_KNOTS = 0.01;

    /**
     * Policy buffer over the limit before an event fires, from {@code event.speedCamera.buffer}
     * (km/h, default 5) converted once to knots. This is the operator-style tolerance; the epsilon
     * above is only the equality guard and applies on top of it.
     */
    private final double bufferKnots;

    @Inject
    public SpeedCameraEventHandler(RedisCache redisCache, Config config) {
        this.redisCache = redisCache;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        double bufferKph = config.getDouble(Keys.EVENT_SPEED_CAMERA_BUFFER);
        if (!(bufferKph >= 0) || Double.isInfinite(bufferKph)) {
            // A negative buffer would fire under the limit; 0 means "anything past the limit".
            LOGGER.warn("event.speedCamera.buffer={} is not a finite value >= 0; using 0 km/h", bufferKph);
            bufferKph = 0;
        }
        this.bufferKnots = UnitsConverter.knotsFromKph(bufferKph);
    }

    long getCameraZoneReads() {
        return cameraZoneReads.get();
    }

    long getReadNoLimit() {
        return readNoLimit.get();
    }

    long getOverLimit() {
        return overLimit.get();
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        if (!position.getValid()) {
            LOGGER.debug("Invalid position received for deviceId={}", position.getDeviceId());
            return;
        }

        long deviceId = position.getDeviceId();
        String cacheKey = "speed_camera:" + deviceId;

        SpeedCameraState cameraState = null;
        try {
            if (redisCache.isAvailable() && redisCache.exists(cacheKey)) {
                String json = redisCache.get(cacheKey);
                LOGGER.debug("Redis cache hit for speedCamera deviceId={}", deviceId);
                cameraState = objectMapper.readValue(json, SpeedCameraState.class);
            } else if (!redisCache.isAvailable() && localCache.containsKey(cacheKey)) {
                String json = localCache.get(cacheKey);
                LOGGER.debug("Local cache hit for speedCamera deviceId={}", deviceId);
                cameraState = objectMapper.readValue(json, SpeedCameraState.class);
            } else {
                LOGGER.debug("No cache found for speedCamera deviceId={}", deviceId);
            }
        } catch (Exception e) {
            LOGGER.warn("Error reading SpeedCameraState for deviceId={}", deviceId, e);
        }

        if (cameraState == null) {
            cameraState = new SpeedCameraState();
            LOGGER.debug("Created new SpeedCameraState for deviceId={}", deviceId);
        }

        // Check if Overpass returned speed_camera (from tollRouteProvider data)
        String highwayTag = position.getString(Position.KEY_HIGHWAY);
        String enforcementTag = position.getString(Position.KEY_ENFORCEMENT);
        LOGGER.debug("Highway tag for deviceId={} is '{}', enforcement tag is '{}'",
                     deviceId, highwayTag, enforcementTag);

        // Get allowed highway types from config
        String allowedHighwayStr = config.getString("event.speedCamera.highwayTypes", "motorway_link");
        Set<String> allowedHighways = Arrays.stream(allowedHighwayStr.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // Get allowed enforcement types from config
        String allowedEnforcementStr = config.getString("event.speedCamera.enforcementTypes", "maxspeed,speed");
        Set<String> allowedEnforcements = Arrays.stream(allowedEnforcementStr.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // Both values are knots: OverpassSpeedLimitProvider.parseSpeed stores the limit in knots and
        // Position.getSpeed() is knots. ExtendedModel.getDouble returns 0.0 for an absent attribute,
        // so "no limit" arrives here as 0.0 and is handled as a non-reading below (plan D1, D2).
        double speedLimitKnots = position.getDouble(Position.KEY_SPEED_LIMIT);
        double speedKnots = position.getSpeed();

        boolean isSpeedCamera = false;

        // Check highway tag
        if (highwayTag != null && allowedHighways.contains(highwayTag.toLowerCase())) {
            isSpeedCamera = true;
            LOGGER.debug("Speed camera detected via highway tag: '{}' in allowed list: {}",
                         highwayTag, allowedHighways);
        }

        // Check enforcement tag
        if (enforcementTag != null && allowedEnforcements.contains(enforcementTag.toLowerCase())) {
            isSpeedCamera = true;
            LOGGER.debug("Speed camera detected via enforcement tag: '{}' in allowed list: {}",
                         enforcementTag, allowedEnforcements);
        }

        long zoneReads = isSpeedCamera ? cameraZoneReads.incrementAndGet() : 0;

        if (!isSpeedCamera) {
            LOGGER.debug("Skipping speed camera: highway='{}', enforcement='{}' not a camera zone for deviceId={}",
                    highwayTag, enforcementTag, deviceId);
        } else if (!(speedLimitKnots > 0) || Double.isInfinite(speedLimitKnots)) {
            // A camera zone with no usable limit is a non-reading, never a 0 km/h zone (plan D2). The
            // negated compare also catches NaN, which parseSpeed accepts and every ordered compare rejects.
            long skipped = readNoLimit.incrementAndGet();
            LOGGER.debug("Skipping speed camera: no speedLimit for deviceId={} in zone highway='{}',"
                    + " enforcement='{}', speed={} kn (readNoLimit={})",
                    deviceId, highwayTag, enforcementTag, speedKnots, skipped);
        } else if (speedKnots > speedLimitKnots + bufferKnots + SPEED_EQUALITY_EPSILON_KNOTS) {
            overLimit.incrementAndGet();
            LOGGER.debug("Speed camera triggered: highway='{}', enforcement='{}', speed={} kn > limit {} kn"
                    + " + buffer {} kn", highwayTag, enforcementTag, speedKnots, speedLimitKnots, bufferKnots);

            cameraState.addDetection(position, confidenceWindow, highwayTag, speedKnots, speedLimitKnots);

        } else {
            LOGGER.debug("Skipping speed camera: highway='{}', enforcement='{}', speed={} kn <= limit {} kn"
                    + " + buffer {} kn", highwayTag, enforcementTag, speedKnots, speedLimitKnots, bufferKnots);
        }

        if (zoneReads > 0 && zoneReads % COUNTER_LOG_INTERVAL == 0) {
            LOGGER.info("SpeedCamera counters since startup: cameraZoneReads={}, readNoLimit={}, overLimit={}",
                    zoneReads, readNoLimit.get(), overLimit.get());
        }

        Event event = cameraState.getEvent();
        if (event != null) {
            event.setDeviceId(deviceId);
            try {
                callback.eventDetected(event);
                LOGGER.info("SpeedCameraEvent emitted for deviceId={}", deviceId);
            } catch (Exception e) {
                LOGGER.warn("Error emitting speed camera event for deviceId={}", deviceId, e);
            }
            cameraState.clearEvent();
        } else {
            LOGGER.debug("No speed camera event for deviceId={} after window check", deviceId);
        }

        try {
            String updatedJson = objectMapper.writeValueAsString(cameraState);
            if (redisCache.isAvailable()) {
                redisCache.setWithTTL(cacheKey, updatedJson, STATE_TTL_SECONDS);
                LOGGER.debug("Updated Redis cache for speedCamera deviceId={}", deviceId);
            } else {
                localCache.put(cacheKey, updatedJson);
                LOGGER.debug("Updated local cache for speedCamera deviceId={}", deviceId);
            }
        } catch (Exception e) {
            LOGGER.warn("Error writing SpeedCameraState for deviceId={}", deviceId, e);
        }

    }
}

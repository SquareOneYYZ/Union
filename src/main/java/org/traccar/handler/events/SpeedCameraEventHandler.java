package org.traccar.handler.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
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

    /** Camera zones seen without a usable limit; exposed for tests and, later, metrics (plan D2). */
    private final AtomicLong readNoLimit = new AtomicLong();

    /**
     * Equality guard, not a tolerance. Device speeds arrive as km/h and are converted to knots by the
     * protocol decoders; the limit is km/h converted by UnitsConverter.knotsFromKph. The two paths can
     * differ in the fifth decimal for the same km/h value (50 km/h: 26.9979 vs 26.9978 kn), and a strict
     * compare then fires on a vehicle travelling exactly at the posted limit. On the pinned export that
     * was 539 of 42,210 events, all within 0.0001 kn; the smallest genuine over-limit reading was
     * 0.26 kn. 0.01 kn (0.02 km/h) sits far from both. Operator tolerances are stage B's calendar.
     */
    static final double SPEED_EQUALITY_EPSILON_KNOTS = 0.01;

    @Inject
    public SpeedCameraEventHandler(RedisCache redisCache, Config config) {
        this.redisCache = redisCache;
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    public long getReadNoLimit() {
        return readNoLimit.get();
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

        if (!isSpeedCamera) {
            LOGGER.debug("Skipping speed camera: highway='{}', enforcement='{}' not a camera zone for deviceId={}",
                    highwayTag, enforcementTag, deviceId);
        } else if (speedLimitKnots <= 0) {
            // A camera zone with no usable limit is a non-reading, never a 0 km/h zone (plan D2).
            long skipped = readNoLimit.incrementAndGet();
            LOGGER.debug("Skipping speed camera: no speedLimit for deviceId={} in zone highway='{}',"
                    + " enforcement='{}', speed={} kn (readNoLimit={})",
                    deviceId, highwayTag, enforcementTag, speedKnots, skipped);
        } else if (speedKnots > speedLimitKnots + SPEED_EQUALITY_EPSILON_KNOTS) {
            LOGGER.debug("Speed camera triggered: highway='{}', enforcement='{}', speed={} kn > limit {} kn",
                    highwayTag, enforcementTag, speedKnots, speedLimitKnots);

            cameraState.addDetection(position, confidenceWindow, highwayTag, speedKnots, speedLimitKnots);

        } else {
            LOGGER.debug("Skipping speed camera: highway='{}', enforcement='{}', speed={} kn <= limit {} kn",
                    highwayTag, enforcementTag, speedKnots, speedLimitKnots);
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

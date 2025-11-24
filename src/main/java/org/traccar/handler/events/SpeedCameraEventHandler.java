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
import java.util.stream.Collectors;

@Singleton
public class SpeedCameraEventHandler extends BaseEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedCameraEventHandler.class);

    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private final Config config;
    private final int confidenceWindow = 1; // could be configurable
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    @Inject
    public SpeedCameraEventHandler(RedisCache redisCache, Config config) {
        this.redisCache = redisCache;
        this.config = config;
        this.objectMapper = new ObjectMapper();
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
        LOGGER.debug("Highway tag for deviceId={} is '{}'", deviceId, highwayTag);

        String allowedHighwayStr = config.getString("event.speedCamera.highwayTypes", "motorway_link");
        Set<String> allowedHighways = Arrays.stream(allowedHighwayStr.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Double speedLimit = position.getDouble(Position.KEY_SPEED_LIMIT);
        double speedKmh = position.getSpeed() * 1.852;

         // If speed limit is missing, skip event
        if (speedLimit == null) {
            LOGGER.debug("Skipping speed camera: No speedLimit provided for deviceId={}, speed={} km/h",
                    deviceId, speedKmh);
        } else if (highwayTag != null
                && allowedHighways.contains(highwayTag.toLowerCase())
                && speedKmh > speedLimit) {
            LOGGER.debug("Speed camera triggered: highway='{}' matched list={}, speed={} km/h > limit {} km/h",
                    highwayTag, allowedHighways, speedKmh, speedLimit);

            cameraState.addDetection(position, confidenceWindow);

        } else {
            LOGGER.debug("Skipping speed camera: highway='{}', allowedList={}, speed={} km/h, limit={}",
                    highwayTag, allowedHighways, speedKmh, speedLimit);
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
                redisCache.set(cacheKey, updatedJson);
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

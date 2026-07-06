package org.traccar.handler.events;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.state.SpeedCameraState;
import org.traccar.storage.localCache.EventStateManager;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class SpeedCameraEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedCameraEventHandler.class);

    private static final String CACHE_KEY_PREFIX = "speed_camera:";

    private static final int CONFIDENCE_WINDOW = 1; // configurable via config if needed

    private final EventStateManager stateManager;
    private final Config config;

    @Inject
    public SpeedCameraEventHandler(EventStateManager stateManager, Config config) {
        this.stateManager = stateManager;
        this.config = config;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!position.getValid()) {
            LOGGER.debug("Invalid position received for deviceId={}", position.getDeviceId());
            return;
        }

        long deviceId = position.getDeviceId();
        String cacheKey = CACHE_KEY_PREFIX + deviceId;

        SpeedCameraState cameraState = stateManager.load(cacheKey, SpeedCameraState.class);
        if (cameraState == null) {
            cameraState = new SpeedCameraState();
            LOGGER.debug("Created new SpeedCameraState for deviceId={}", deviceId);
        }

        String highwayTag     = position.getString(Position.KEY_HIGHWAY);
        String enforcementTag = position.getString(Position.KEY_ENFORCEMENT);
        LOGGER.debug("Highway='{}', enforcement='{}' for deviceId={}", highwayTag, enforcementTag, deviceId);

        Set<String> allowedHighways = parseConfigSet(
                config.getString("event.speedCamera.highwayTypes", "motorway_link"));

        Set<String> allowedEnforcements = parseConfigSet(
                config.getString("event.speedCamera.enforcementTypes", "maxspeed,speed"));

        Double speedLimit = position.getDouble(Position.KEY_SPEED_LIMIT);
        double speedKmh   = position.getSpeed() * 1.852;

        boolean isSpeedCamera = false;

        if (highwayTag != null && allowedHighways.contains(highwayTag.toLowerCase())) {
            isSpeedCamera = true;
            LOGGER.debug("Speed camera via highway tag: '{}' in {}", highwayTag, allowedHighways);
        }
        if (enforcementTag != null && allowedEnforcements.contains(enforcementTag.toLowerCase())) {
            isSpeedCamera = true;
            LOGGER.debug("Speed camera via enforcement tag: '{}' in {}", enforcementTag, allowedEnforcements);
        }

        if (speedLimit == null) {
            LOGGER.debug("Skipping speed camera: no speedLimit for deviceId={}, speed={} km/h",
                    deviceId, speedKmh);
        } else if (isSpeedCamera && speedKmh > speedLimit) {
            LOGGER.debug("Speed camera triggered: highway='{}', enforcement='{}', speed={} km/h > limit={} km/h",
                    highwayTag, enforcementTag, speedKmh, speedLimit);
            cameraState.addDetection(position, CONFIDENCE_WINDOW, highwayTag, speedKmh, speedLimit);
        } else {
            LOGGER.debug("Skipping speed camera: highway='{}', enforcement='{}', isCamera={}, "
                    + "speed={} km/h, limit={}", highwayTag, enforcementTag, isSpeedCamera, speedKmh, speedLimit);
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

        stateManager.save(cacheKey, cameraState);
    }


    private static Set<String> parseConfigSet(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }
}

package org.traccar.handler.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;
import org.traccar.session.state.SurfaceState;
import org.traccar.storage.localCache.RedisCache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class SurfaceEventHandler extends BaseEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SurfaceEventHandler.class);

    private Set<String> getAlertSurfaces() {
        String surfaces = config.getString(Keys.EVENT_SURFACE_ALERT_TYPES, "");
        return surfaces.isEmpty()
                ? Set.of()
                : Set.of(surfaces.toLowerCase().split("\\s*,\\s*"));
    }


    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private final int confidenceWindow = 4;
    private final Config config;
    private final Map<String, String> localCache = new ConcurrentHashMap<>();



    @Inject
    public SurfaceEventHandler(RedisCache redisCache, Config config) {
        this.redisCache = redisCache;
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        String surface = position.getString(Position.KEY_SURFACE);
        Set<String> alertSurfaces = getAlertSurfaces();
        if (surface == null || !alertSurfaces.contains(surface.toLowerCase())) {
            LOGGER.debug("SurfaceEventHandler skipped: invalid or non-alert surface '{}'", surface);
            return;
        }

        long deviceId = position.getDeviceId();
        String cacheKey = "surface:" + deviceId;
        SurfaceState surfaceState = null;

        try {
//            if (redisCache.exists(cacheKey)) {
//                String json = redisCache.get(cacheKey);
//                surfaceState = objectMapper.readValue(json, SurfaceState.class);
//                LOGGER.debug("Loaded SurfaceState from Redis for deviceId={}", deviceId);
//            }

            if (redisCache.isAvailable() && redisCache.exists(cacheKey)) {
                String json = redisCache.get(cacheKey);
                surfaceState = objectMapper.readValue(json, SurfaceState.class);
                LOGGER.debug("Loaded SurfaceState from Redis for deviceId={}", deviceId);
            } else if (!redisCache.isAvailable() && localCache.containsKey(cacheKey)) {
                String json = localCache.get(cacheKey);
                surfaceState = objectMapper.readValue(json, SurfaceState.class);
                LOGGER.debug("Loaded SurfaceState from local cache for deviceId={}", deviceId);
            }

        } catch (Exception e) {
            LOGGER.warn("Error reading SurfaceState from Redis for deviceId={}", deviceId, e);
        }

        if (surfaceState == null) {
            surfaceState = new SurfaceState();
        }

        surfaceState.addSurface(surface.toLowerCase(), confidenceWindow, position);

        try {
//            String updatedJson = objectMapper.writeValueAsString(surfaceState);
//            redisCache.set(cacheKey, updatedJson);
//            LOGGER.debug("Updated SurfaceState in Redis for deviceId={}", deviceId);

            String updatedJson = objectMapper.writeValueAsString(surfaceState);
            if (redisCache.isAvailable()) {
                redisCache.set(cacheKey, updatedJson);
                LOGGER.debug("Updated SurfaceState in Redis for deviceId={}", deviceId);
            } else {
                localCache.put(cacheKey, updatedJson);
                LOGGER.debug("Updated SurfaceState in local cache for deviceId={}", deviceId);
            }

        } catch (Exception e) {
            LOGGER.warn("Error writing SurfaceState to Redis for deviceId={}", deviceId, e);
        }

        if (surfaceState.getEvent() != null) {
            surfaceState.getEvent().setDeviceId(deviceId);
            surfaceState.getEvent().set(Position.KEY_SURFACE, surface);
            LOGGER.info("Triggering surface event for deviceId={} with surface={}", deviceId, surface);
            try {
            callback.eventDetected(surfaceState.getEvent());
            LOGGER.info("SurfaceEvent emitted: {}", surface);
            } catch (Exception e) {
                LOGGER.warn("Error emitting surface event for deviceId={}", deviceId, e);
            }
        } else {
            LOGGER.info("No surface event generated for deviceId={} after window check", deviceId);
        }
    }
}

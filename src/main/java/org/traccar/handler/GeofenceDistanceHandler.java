package org.traccar.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.handler.events.BaseEventHandler;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.GeofenceDistanceProcessor;
import org.traccar.session.state.GeofenceDistanceState;
import org.traccar.storage.localCache.RedisCache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeofenceDistanceHandler extends BaseEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceHandler.class);

    @Inject
    private CacheManager cacheManager;

    @Inject
    private RedisCache redisCache;

    private final Map<String, String> localCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();
        String cacheKey = "geofenceDist:" + deviceId;

        GeofenceDistanceState state = null;
        try {
            if (redisCache.isAvailable() && redisCache.exists(cacheKey)) {
                state = mapper.readValue(redisCache.get(cacheKey), GeofenceDistanceState.class);
            } else if (!redisCache.isAvailable() && localCache.containsKey(cacheKey)) {
                state = mapper.readValue(localCache.get(cacheKey), GeofenceDistanceState.class);
            }
        } catch (Exception e) {
            LOGGER.warn("Error loading GeofenceDistanceState: {}", e.getMessage());
        }

        if (state == null) {
            state = new GeofenceDistanceState();
        }

        GeofenceDistanceProcessor.updateState(state, position);

        // 3. Save state back to cache
        try {
            String json = mapper.writeValueAsString(state);
            if (redisCache.isAvailable()) {
                redisCache.set(cacheKey, json);
            } else {
                localCache.put(cacheKey, json);
            }
        } catch (Exception e) {
            LOGGER.warn("Error writing geofence state for deviceId={}", deviceId, e);
        }

        LOGGER.info("Updated geofence distance state: dev={}, geo={}, km={}",
                deviceId, state.getCurrentGeofence(), state.getTravelledDistance());
    }
}

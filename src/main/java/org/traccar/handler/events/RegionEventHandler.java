package org.traccar.handler.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Position;
import org.traccar.session.state.RegionState;
import org.traccar.storage.localCache.RedisCache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class RegionEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionEventHandler.class);

    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    @Inject
    public RegionEventHandler(RedisCache redisCache) {
        this.redisCache = redisCache;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        long deviceId = position.getDeviceId();
        String cacheKey = "region:" + deviceId;

        String country = position.getString(Position.KEY_COUNTRY);
        String state = position.getString(Position.KEY_STATE);
        String city = position.getString(Position.KEY_CITY);

        if (country == null && state == null && city == null) {
            return;
        }

        RegionState regionState = null;

        try {
            if (redisCache.isAvailable() && redisCache.exists(cacheKey)) {
                String json = redisCache.get(cacheKey);
                regionState = objectMapper.readValue(json, RegionState.class);
                LOGGER.debug("Loaded RegionState from Redis cache for deviceId={}", deviceId);
            } else if (!redisCache.isAvailable() && localCache.containsKey(cacheKey)) {
                String json = localCache.get(cacheKey);
                regionState = objectMapper.readValue(json, RegionState.class);
                LOGGER.debug("Loaded RegionState from local cache for deviceId={}", deviceId);
            }
        } catch (Exception e) {
            LOGGER.warn("Error reading RegionState from Redis for deviceId={}", deviceId, e);
        }

        if (regionState == null) {
            regionState = new RegionState();
        }

        regionState.updateRegion(country, state, city, position);

        try {
            String updatedJson = objectMapper.writeValueAsString(regionState);
            if (redisCache.isAvailable()) {
                redisCache.set(cacheKey, updatedJson);
            } else {
                localCache.put(cacheKey, updatedJson);
            }
        } catch (Exception e) {
            LOGGER.warn("Error writing RegionState to Redis for deviceId={}", deviceId, e);
        }

        // Trigger all specific region events

// Country first
        if (regionState.getCountryExitEvent() != null) {
            regionState.getCountryExitEvent().setDeviceId(deviceId);
            callback.eventDetected(regionState.getCountryExitEvent());
        }
        if (regionState.getCountryEnterEvent() != null) {
            regionState.getCountryEnterEvent().setDeviceId(deviceId);
            callback.eventDetected(regionState.getCountryEnterEvent());
        }

// State next
        if (regionState.getStateExitEvent() != null) {
            regionState.getStateExitEvent().setDeviceId(deviceId);
            callback.eventDetected(regionState.getStateExitEvent());
        }
        if (regionState.getStateEnterEvent() != null) {
            regionState.getStateEnterEvent().setDeviceId(deviceId);
            callback.eventDetected(regionState.getStateEnterEvent());
        }

// City last
        if (regionState.getCityExitEvent() != null) {
            regionState.getCityExitEvent().setDeviceId(deviceId);
            callback.eventDetected(regionState.getCityExitEvent());
        }
        if (regionState.getCityEnterEvent() != null) {
            regionState.getCityEnterEvent().setDeviceId(deviceId);
            callback.eventDetected(regionState.getCityEnterEvent());
        }




    }
}

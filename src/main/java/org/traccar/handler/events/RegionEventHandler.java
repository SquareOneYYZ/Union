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
        LOGGER.debug("RegionEventHandler received position for deviceId={}", deviceId);
        String cacheKey = "region:" + deviceId;

        String country = position.getString(Position.KEY_COUNTRY);
        String state = position.getString(Position.KEY_STATE);
        String city = position.getString(Position.KEY_CITY);

        if (country == null && state == null && city == null) {
            LOGGER.debug("No region info in position, skipping deviceId={}", deviceId);
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
            LOGGER.debug("Created new RegionState for deviceId={}", deviceId);
        }

        regionState.updateRegion(country, state, city, position);

        try {
            String updatedJson = objectMapper.writeValueAsString(regionState);
            if (redisCache.isAvailable()) {
                redisCache.set(cacheKey, updatedJson);
            } else {
                localCache.put(cacheKey, updatedJson);
                LOGGER.debug("Stored RegionState in local cache for deviceId={}", deviceId);
            }
        } catch (Exception e) {
            LOGGER.warn("Error writing RegionState to Redis for deviceId={}", deviceId, e);
        }

        // Trigger all specific region events
        LOGGER.debug("Checking region events for deviceId={}", deviceId);
// Country first
        if (regionState.getCountryExitEvent() != null) {
            regionState.getCountryExitEvent().setDeviceId(deviceId);
            LOGGER.debug(" Triggering COUNTRY EXIT event: {} -> {}",
                    regionState.getCountryExitEvent().getString(Position.KEY_COUNTRY),
                    country);
            callback.eventDetected(regionState.getCountryExitEvent());
        }
        if (regionState.getCountryEnterEvent() != null) {
            LOGGER.debug("Country Event ALL Attributes: {}",
                    regionState.getCountryEnterEvent().getAttributes());
            regionState.getCountryEnterEvent().setDeviceId(deviceId);
            LOGGER.debug(" Triggering COUNTRY ENTER event: {}",
                    regionState.getCountryEnterEvent().getString(Position.KEY_COUNTRY));
            callback.eventDetected(regionState.getCountryEnterEvent());
        }
        LOGGER.debug("RegionEventHandler completed for device: {}", position.getDeviceId());

// State next
        if (regionState.getStateExitEvent() != null) {
            regionState.getStateExitEvent().setDeviceId(deviceId);
            LOGGER.debug(" Triggering STATE EXIT event: {} -> {}",
                    regionState.getStateExitEvent().getString(Position.KEY_STATE),
                    state);
            callback.eventDetected(regionState.getStateExitEvent());
        }
        if (regionState.getStateEnterEvent() != null) {
            regionState.getStateEnterEvent().setDeviceId(deviceId);
            LOGGER.debug(" Triggering STATE ENTER event: {}",
                    regionState.getStateEnterEvent().getString(Position.KEY_STATE));
            callback.eventDetected(regionState.getStateEnterEvent());
        }

// City last
        if (regionState.getCityExitEvent() != null) {
            regionState.getCityExitEvent().setDeviceId(deviceId);
            LOGGER.debug(" Triggering CITY EXIT event: {} -> {}",
                    regionState.getCityExitEvent().getString(Position.KEY_CITY),
                    city);
            callback.eventDetected(regionState.getCityExitEvent());
        }
        if (regionState.getCityEnterEvent() != null) {
            regionState.getCityEnterEvent().setDeviceId(deviceId);
            LOGGER.debug(" Triggering CITY ENTER event: {}",
                    regionState.getCityEnterEvent().getString(Position.KEY_CITY));
            callback.eventDetected(regionState.getCityEnterEvent());
        }


    }
}

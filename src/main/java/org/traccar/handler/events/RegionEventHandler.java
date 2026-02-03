package org.traccar.handler.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Position;
import org.traccar.model.Region;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.RegionState;
import org.traccar.storage.localCache.RedisCache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class RegionEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionEventHandler.class);

    private final CacheManager cacheManager;
    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    @Inject
    public RegionEventHandler(CacheManager cacheManager, RedisCache redisCache) {
        this.cacheManager = cacheManager;
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

        // Get linked regions for filtering
        Set<Region> linkedRegions = cacheManager.getDeviceObjects(deviceId, Region.class);
        boolean hasFilter = !linkedRegions.isEmpty();
        LOGGER.debug("Device {} has {} linked regions, filter active: {}", deviceId, linkedRegions.size(), hasFilter);

        // Trigger all specific region events
        LOGGER.debug("Checking region events for deviceId={}", deviceId);
        if (regionState.getCountryExitEvent() != null) {
            String exitCountry = regionState.getCountryExitEvent().getString(Position.KEY_COUNTRY);
            if (!hasFilter || matchesRegion(linkedRegions, "country", exitCountry, null, null)) {
                regionState.getCountryExitEvent().setDeviceId(deviceId);
                LOGGER.debug(" Triggering COUNTRY EXIT event: {} -> {}", exitCountry, country);
                callback.eventDetected(regionState.getCountryExitEvent());
            } else {
                LOGGER.debug(" Skipping COUNTRY EXIT event (not in filter): {}", exitCountry);
            }
        }
        if (regionState.getCountryEnterEvent() != null) {
            String enterCountry = regionState.getCountryEnterEvent().getString(Position.KEY_COUNTRY);
            if (!hasFilter || matchesRegion(linkedRegions, "country", enterCountry, null, null)) {
                LOGGER.debug("Country Event ALL Attributes: {}", regionState.getCountryEnterEvent().getAttributes());
                regionState.getCountryEnterEvent().setDeviceId(deviceId);
                LOGGER.debug(" Triggering COUNTRY ENTER event: {}", enterCountry);
                callback.eventDetected(regionState.getCountryEnterEvent());
            } else {
                LOGGER.debug(" Skipping COUNTRY ENTER event (not in filter): {}", enterCountry);
            }
        }
        LOGGER.debug("RegionEventHandler completed for device: {}", position.getDeviceId());

        if (regionState.getStateExitEvent() != null) {
            String exitState = regionState.getStateExitEvent().getString(Position.KEY_STATE);
            if (!hasFilter || matchesRegion(linkedRegions, "state", exitState, country, null)) {
                regionState.getStateExitEvent().setDeviceId(deviceId);
                LOGGER.debug(" Triggering STATE EXIT event: {} -> {}", exitState, state);
                callback.eventDetected(regionState.getStateExitEvent());
            } else {
                LOGGER.debug(" Skipping STATE EXIT event (not in filter): {}", exitState);
            }
        }
        if (regionState.getStateEnterEvent() != null) {
            String enterState = regionState.getStateEnterEvent().getString(Position.KEY_STATE);
            if (!hasFilter || matchesRegion(linkedRegions, "state", enterState, country, null)) {
                regionState.getStateEnterEvent().setDeviceId(deviceId);
                LOGGER.debug(" Triggering STATE ENTER event: {}", enterState);
                callback.eventDetected(regionState.getStateEnterEvent());
            } else {
                LOGGER.debug(" Skipping STATE ENTER event (not in filter): {}", enterState);
            }
        }

        if (regionState.getCityExitEvent() != null) {
            String exitCity = regionState.getCityExitEvent().getString(Position.KEY_CITY);
            if (!hasFilter || matchesRegion(linkedRegions, "city", exitCity, country, state)) {
                regionState.getCityExitEvent().setDeviceId(deviceId);
                LOGGER.debug(" Triggering CITY EXIT event: {} -> {}", exitCity, city);
                callback.eventDetected(regionState.getCityExitEvent());
            } else {
                LOGGER.debug(" Skipping CITY EXIT event (not in filter): {}", exitCity);
            }
        }
        if (regionState.getCityEnterEvent() != null) {
            String enterCity = regionState.getCityEnterEvent().getString(Position.KEY_CITY);
            if (!hasFilter || matchesRegion(linkedRegions, "city", enterCity, country, state)) {
                regionState.getCityEnterEvent().setDeviceId(deviceId);
                LOGGER.debug(" Triggering CITY ENTER event: {}", enterCity);
                callback.eventDetected(regionState.getCityEnterEvent());
            } else {
                LOGGER.debug(" Skipping CITY ENTER event (not in filter): {}", enterCity);
            }
        }
    }

    private boolean matchesRegion(Set<Region> linkedRegions, String type, String value,
                                  String country, String state) {
        if (value == null) return false;

        return linkedRegions.stream().anyMatch(region -> {
            if (!region.getType().equals(type)) return false;
            if (!region.getValue().equalsIgnoreCase(value)) return false;

            switch (type) {
                case "city":
                    return region.getCountry().equalsIgnoreCase(country)
                            && region.getState().equalsIgnoreCase(state);
                case "state":
                    return region.getCountry().equalsIgnoreCase(country);
                default: // country
                    return true;
            }
        });
    }



}


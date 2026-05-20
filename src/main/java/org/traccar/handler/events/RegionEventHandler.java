package org.traccar.handler.events;

import com.google.inject.Singleton;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Position;
import org.traccar.model.Region;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.RegionState;
import org.traccar.storage.localCache.EventStateManager;

import java.util.Set;

@Singleton
public class RegionEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionEventHandler.class);

    private static final String CACHE_KEY_PREFIX = "region:";

    private final CacheManager cacheManager;
    private final EventStateManager stateManager;

    @Inject
    public RegionEventHandler(CacheManager cacheManager, EventStateManager stateManager) {
        this.cacheManager = cacheManager;
        this.stateManager = stateManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        long deviceId = position.getDeviceId();
        LOGGER.debug("RegionEventHandler received position for deviceId={}", deviceId);

        if (position.getBoolean("regionLookupFailed")) {
            LOGGER.debug("Skipping region processing due to lookup failure for deviceId={}", deviceId);
            return;
        }

        String country = position.getString(Position.KEY_COUNTRY);
        String state   = position.getString(Position.KEY_STATE);
        String city    = position.getString(Position.KEY_CITY);

        if (country == null && state == null && city == null) {
            LOGGER.debug("No region info in position, skipping deviceId={}", deviceId);
            return;
        }

        String cacheKey = CACHE_KEY_PREFIX + deviceId;

        RegionState regionState = stateManager.load(cacheKey, RegionState.class);
        if (regionState == null) {
            regionState = new RegionState();
            LOGGER.debug("Created new RegionState for deviceId={}", deviceId);
        }

        regionState.updateRegion(country, state, city, position);

        stateManager.save(cacheKey, regionState);

        Set<Region> linkedRegions = cacheManager.getDeviceObjects(deviceId, Region.class);
        boolean hasFilter = !linkedRegions.isEmpty();
        LOGGER.debug("Device {} has {} linked regions, filter active: {}",
                deviceId, linkedRegions.size(), hasFilter);

        if (regionState.getCountryExitEvent() != null) {
            String exitCountry = regionState.getCountryExitEvent().getString(Position.KEY_COUNTRY);
            if (!hasFilter || matchesRegion(linkedRegions, "country", exitCountry, null, null)) {
                regionState.getCountryExitEvent().setDeviceId(deviceId);
                LOGGER.debug("Triggering COUNTRY EXIT event: {} -> {}", exitCountry, country);
                callback.eventDetected(regionState.getCountryExitEvent());
                regionState.setCountryExitEvent(null);
            } else {
                LOGGER.debug("Skipping COUNTRY EXIT event (not in filter): {}", exitCountry);
            }
        }
        if (regionState.getCountryEnterEvent() != null) {
            String enterCountry = regionState.getCountryEnterEvent().getString(Position.KEY_COUNTRY);
            if (!hasFilter || matchesRegion(linkedRegions, "country", enterCountry, null, null)) {
                regionState.getCountryEnterEvent().setDeviceId(deviceId);
                LOGGER.debug("Triggering COUNTRY ENTER event: {}", enterCountry);
                callback.eventDetected(regionState.getCountryEnterEvent());
                regionState.setCountryEnterEvent(null);
            } else {
                LOGGER.debug("Skipping COUNTRY ENTER event (not in filter): {}", enterCountry);
            }
        }

        if (regionState.getStateExitEvent() != null) {
            String exitState   = regionState.getStateExitEvent().getString(Position.KEY_STATE);
            String exitCountry = regionState.getStateExitEvent().getString(Position.KEY_COUNTRY);
            if (!hasFilter || matchesRegion(linkedRegions, "state", exitState, exitCountry, null)) {
                regionState.getStateExitEvent().setDeviceId(deviceId);
                LOGGER.debug("Triggering STATE EXIT event: {} -> {}", exitState, state);
                callback.eventDetected(regionState.getStateExitEvent());
                regionState.setStateExitEvent(null);
            } else {
                LOGGER.debug("Skipping STATE EXIT event (not in filter): {}", exitState);
            }
        }
        if (regionState.getStateEnterEvent() != null) {
            String enterState = regionState.getStateEnterEvent().getString(Position.KEY_STATE);
            if (!hasFilter || matchesRegion(linkedRegions, "state", enterState, country, null)) {
                regionState.getStateEnterEvent().setDeviceId(deviceId);
                LOGGER.debug("Triggering STATE ENTER event: {}", enterState);
                callback.eventDetected(regionState.getStateEnterEvent());
                regionState.setStateEnterEvent(null);
            } else {
                LOGGER.debug("Skipping STATE ENTER event (not in filter): {}", enterState);
            }
        }

        if (regionState.getCityExitEvent() != null) {
            String exitCity    = regionState.getCityExitEvent().getString(Position.KEY_CITY);
            String exitCountry = regionState.getCityExitEvent().getString(Position.KEY_COUNTRY);
            String exitState   = regionState.getCityExitEvent().getString(Position.KEY_STATE);
            if (!hasFilter || matchesRegion(linkedRegions, "city", exitCity, exitCountry, exitState)) {
                regionState.getCityExitEvent().setDeviceId(deviceId);
                LOGGER.debug("Triggering CITY EXIT event: {} -> {}", exitCity, city);
                callback.eventDetected(regionState.getCityExitEvent());
                regionState.setCityExitEvent(null);
            } else {
                LOGGER.debug("Skipping CITY EXIT event (not in filter): {}", exitCity);
            }
        }
        if (regionState.getCityEnterEvent() != null) {
            String enterCity = regionState.getCityEnterEvent().getString(Position.KEY_CITY);
            if (!hasFilter || matchesRegion(linkedRegions, "city", enterCity, country, state)) {
                regionState.getCityEnterEvent().setDeviceId(deviceId);
                LOGGER.debug("Triggering CITY ENTER event: {}", enterCity);
                callback.eventDetected(regionState.getCityEnterEvent());
                regionState.setCityEnterEvent(null);
            } else {
                LOGGER.debug("Skipping CITY ENTER event (not in filter): {}", enterCity);
            }
        }

        LOGGER.debug("RegionEventHandler completed for deviceId={}", deviceId);
    }

    private boolean matchesRegion(Set<Region> linkedRegions, String type, String value,
                                  String country, String state) {
        if (value == null) {
            return false;
        }
        return linkedRegions.stream().anyMatch(region -> {
            if (!region.getType().equals(type)) {
                return false;
            }
            if (!region.getValue().equalsIgnoreCase(value)) {
                return false;
            }
            switch (type) {
                case "city":
                    return region.getCountry() != null
                            && region.getState() != null
                            && country != null
                            && state != null
                            && region.getCountry().equalsIgnoreCase(country)
                            && region.getState().equalsIgnoreCase(state);
                case "state":
                    return region.getCountry() != null
                            && country != null
                            && region.getCountry().equalsIgnoreCase(country);
                default: // country
                    return true;
            }
        });
    }
}

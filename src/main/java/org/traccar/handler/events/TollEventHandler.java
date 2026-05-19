package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Group;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.TollRouteProcessor;
import org.traccar.session.state.TollRouteState;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.localCache.EventStateManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Arrays;


public class TollEventHandler extends BaseEventHandler {

    public static final Logger LOGGER = LoggerFactory.getLogger(TollEventHandler.class);

    private static final String CACHE_KEY_PREFIX = "toll:";

    private final CacheManager cacheManager;
    private final Storage storage;
    private final EventStateManager stateManager;
    private final int minimalDuration;

    @Inject
    public TollEventHandler(Config config, CacheManager cacheManager, Storage storage,
                            EventStateManager stateManager) {
        this.cacheManager = cacheManager;
        this.storage = storage;
        this.stateManager = stateManager;
        this.minimalDuration = config.getInteger(Keys.EVENT_TOLL_ROUTE_MINIMAL_DURATION);
    }


    @Override
    public void onPosition(Position position, Callback callback) {
        long deviceId = position.getDeviceId();

        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device == null) {
            return;
        }

        if (!PositionUtil.isLatest(cacheManager, position) || !position.getValid()) {
            return;
        }

        String groupCustomToll = resolveCustomToll(cacheManager.getObject(Group.class, device.getGroupId()));
        String deviceCustomToll = device.hasAttribute("customRoadEvent")
                ? device.getString("customRoadEvent") : null;

        LOGGER.debug("Group customToll={}, Device customToll={}", groupCustomToll, deviceCustomToll);

        String positionTollRef  = position.getString(Position.KEY_TOLL_REF);
        Boolean positionIsToll  = position.getBoolean(Position.KEY_TOLL);
        String positionTollName = position.getString(Position.KEY_TOLL_NAME);

        String cacheKey = CACHE_KEY_PREFIX + deviceId;
        TollRouteState tollState = stateManager.load(cacheKey, TollRouteState.class);
        if (tollState == null) {
            tollState = new TollRouteState();
            tollState.fromDevice(device);
        }
        tollState.addOnToll(positionIsToll, minimalDuration);
        TollRouteProcessor.updateState(tollState, position, positionTollRef, positionTollName,
                minimalDuration);

        boolean isCustomMatch = matchesCustomToll(positionTollName, groupCustomToll, deviceCustomToll);
        LOGGER.debug("CustomToll compare: positionTollName='{}' vs group='{}', device='{}' => {}",
                positionTollName, groupCustomToll, deviceCustomToll, isCustomMatch);

        tollState.addOnCustomToll(isCustomMatch, minimalDuration);

        if (tollState.isCustomTollConfirmed(minimalDuration)) {
            String lastCustom = tollState.getLastCustomTollName();
            if (lastCustom == null || !lastCustom.equals(positionTollName)) {
                Event event = new Event(Event.TYPE_DEVICE_CUSTOM_TOLL, position);
                event.set(Position.KEY_TOLL_REF, tollState.getTollRef());
                event.set(Position.KEY_TOLL_NAME, positionTollName);
                event.set(Position.KEY_TOTAL_DISTANCE, position.getDouble(Position.KEY_TOTAL_DISTANCE));
                event.set("deviceId", position.getDeviceId());
                event.set("latitude", position.getLatitude());
                event.set("longitude", position.getLongitude());

                tollState.setLastCustomTollName(positionTollName);
                tollState.setEvent(event);

                LOGGER.debug("Custom toll CONFIRMED (threshold={}%): {}",
                        TollRouteState.THRESHOLD_PERCENT, positionTollName);
            }
        }

        Boolean tollConfidence = tollState.isOnToll(minimalDuration);
        if (tollConfidence != null || positionIsToll != null) {
            stateManager.save(cacheKey, tollState);
        }

        if (tollState.isChanged()) {
            tollState.toDevice(device);
            try {
                storage.updateObject(device, new Request(
                        new Columns.Include("tollStartDistance", "tollrouteTime", "attributes"),
                        new Condition.Equals("id", device.getId())));
            } catch (StorageException e) {
                LOGGER.warn("Update device Toll error", e);
            }
        }

        if (tollState.getEvent() != null) {
            callback.eventDetected(tollState.getEvent());
        }
    }


    private static String resolveCustomToll(Group group) {
        if (group != null && group.hasAttribute("customRoadEvent")) {
            return group.getString("customRoadEvent");
        }
        return null;
    }

    private static boolean matchesCustomToll(String positionTollName,
                                             String groupCustomToll,
                                             String deviceCustomToll) {
        if (positionTollName == null) {
            return false;
        }
        String toll = positionTollName.trim();

        if (groupCustomToll != null) {
            boolean match = Arrays.stream(groupCustomToll.split(","))
                    .map(String::trim)
                    .anyMatch(name -> toll.equalsIgnoreCase(name));
            if (match) {
                return true;
            }
        }

        if (deviceCustomToll != null) {
            return Arrays.stream(deviceCustomToll.split(","))
                    .map(String::trim)
                    .anyMatch(name -> toll.equalsIgnoreCase(name));
        }

        return false;
    }
}

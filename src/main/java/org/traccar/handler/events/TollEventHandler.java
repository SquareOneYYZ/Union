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
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.storage.localCache.RedisCache;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class TollEventHandler extends BaseEventHandler {

    public static final Logger LOGGER = LoggerFactory.getLogger(TollEventHandler.class);

    private final CacheManager cacheManager;
    private final Storage storage;
    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;

    private final int minimalDuration;
    private final Map<String, String> localCache = new ConcurrentHashMap<>();
//    private final Set<String> customTollNames;

    @Inject
    public TollEventHandler(Config config, CacheManager cacheManager, Storage storage, RedisCache redisCache) {
        this.cacheManager = cacheManager;
        this.storage = storage;
        this.redisCache = redisCache;
        this.minimalDuration = config.getInteger(Keys.EVENT_TOLL_ROUTE_MINIMAL_DURATION);
        this.objectMapper = new ObjectMapper();

//        String list = config.getString(Keys.EVENT_CUSTOM_TOLL_NAMES, "");
//        this.customTollNames = Arrays.stream(list.split(","))
//                .map(String::trim)
//                .filter(s -> !s.isEmpty())
//                .collect(Collectors.toSet());
//        LOGGER.debug("Custom toll names loaded: {}", customTollNames);

    }

    @Override
    public void onPosition(Position position, Callback callback) {
        long deviceId = position.getDeviceId();
        String cacheKey = String.format("%s", deviceId);
        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device == null) {
            return;
        }
        //  Load group attribute for custom toll
        Group group = cacheManager.getObject(Group.class, device.getGroupId());
        String groupCustomToll = null;
        if (group != null && group.hasAttribute("customRoadEvent")) {
            groupCustomToll = group.getString("customRoadEvent");
            LOGGER.debug("Group custom toll loaded for deviceId {}: {}", deviceId, groupCustomToll);
        }
        // Load device attribute for custom toll
        String deviceCustomToll = null;
        if (device != null && device.hasAttribute("customRoadEvent")) {
            deviceCustomToll = device.getString("customRoadEvent");
            LOGGER.debug("Device custom toll loaded for deviceId {}: {}", deviceId, deviceCustomToll);
        }


        if (!PositionUtil.isLatest(cacheManager, position) || !position.getValid()) {
            return;
        }
        String positionTollRef = position.getString(Position.KEY_TOLL_REF);
        Boolean positionIsToll = position.getBoolean(Position.KEY_TOLL);
        String positionTollName = position.getString(Position.KEY_TOLL_NAME);

        TollRouteState tollState = null;
        try {
            if (redisCache.isAvailable() && redisCache.exists(cacheKey)) {
                String json = redisCache.get(cacheKey);
                LOGGER.debug("Redis hit for deviceId={}", deviceId);
                tollState = objectMapper.readValue(json, TollRouteState.class);
            } else if (!redisCache.isAvailable() && localCache.containsKey(cacheKey)) {
                String json = localCache.get(cacheKey);
                LOGGER.debug("Local cache hit for deviceId={}", deviceId);
                tollState = objectMapper.readValue(json, TollRouteState.class);
            }
        } catch (Exception e) {
            LOGGER.warn("Redis read error for deviceId={}", deviceId, e);
        }
        if (tollState == null) {
            tollState = new TollRouteState();
            tollState.fromDevice(device);
        }
        tollState.addOnToll(positionIsToll, minimalDuration);
        TollRouteProcessor.updateState(tollState, position, positionTollRef, positionTollName, minimalDuration);

        LOGGER.debug("Position tollName={}, Group customToll={}, Device customToll={}", positionTollName,
                groupCustomToll, deviceCustomToll);

//        boolean isCustomMatch = positionTollName != null
//                && groupCustomToll != null
//                && positionTollName.equalsIgnoreCase(groupCustomToll);

        boolean isCustomMatch = false;
        if (positionTollName != null) {
            String toll = positionTollName.trim();

            // Check group custom tolls
            if (groupCustomToll != null) {
                isCustomMatch = Arrays.stream(groupCustomToll.split(","))
                        .map(String::trim)
                        .anyMatch(name -> toll.equalsIgnoreCase(name));
            }

            // If not matched in group, check device custom tolls
            if (!isCustomMatch && deviceCustomToll != null) {
                isCustomMatch = Arrays.stream(deviceCustomToll.split(","))
                        .map(String::trim)
                        .anyMatch(name -> toll.equalsIgnoreCase(name));
            }
        }
        LOGGER.debug("CustomToll compare: positionTollName='{}' vs groupCustomToll='{}', deviceCustomToll='{}' => {}",
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

                LOGGER.debug("Custom toll CONFIRMED after {} points: {}", minimalDuration, positionTollName);
            }
        }


        Boolean tollConfidence = tollState.isOnToll(minimalDuration);
        if (tollConfidence != null || positionIsToll != null) {
            try {
                String json = objectMapper.writeValueAsString(tollState);
                if (redisCache.isAvailable()) {
                    redisCache.set(cacheKey, json);
                } else {
                    localCache.put(cacheKey, json);
                }

            } catch (Exception e) {
                LOGGER.warn("Redis write error for deviceId={}", deviceId, e);
            }
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

}

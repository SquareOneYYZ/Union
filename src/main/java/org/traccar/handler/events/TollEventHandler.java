package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class TollEventHandler extends BaseEventHandler {

    public static final Logger LOGGER = LoggerFactory.getLogger(TollEventHandler.class);


    private final CacheManager cacheManager;
    private final Storage storage;
  //  private final LocalCache localCache;
   private final RedisCache redisCache;
    private final ObjectMapper objectMapper;

    private final int minimalDuration;
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    @Inject
    public TollEventHandler(Config config, CacheManager cacheManager, Storage storage, RedisCache redisCache) {
        this.cacheManager = cacheManager;
        this.storage = storage;
        this.redisCache = redisCache;
        this.minimalDuration = config.getInteger(Keys.EVENT_TOLL_ROUTE_MINIMAL_DURATION);
        this.objectMapper = new ObjectMapper();
    }



@Override
public void onPosition(Position position, Callback callback) {
    long deviceId = position.getDeviceId();
    String cacheKey = String.format("%s", deviceId);

    Device device = cacheManager.getObject(Device.class, deviceId);
    if (device == null) {
        return;
    }
    if (!PositionUtil.isLatest(cacheManager, position) || !position.getValid()) {
        return;
    }

    String positionTollRef = position.getString(Position.KEY_TOLL_REF);
    Boolean positionIsToll = position.getBoolean(Position.KEY_TOLL);
    String positionTollName = position.getString(Position.KEY_TOLL_NAME);

    // Load state from Redis
    TollRouteState tollState = null;
    try {
//        if (redisCache.exists(cacheKey)) {
//            String json = redisCache.get(cacheKey);
//            LOGGER.debug("Redis hit for deviceId={}", deviceId);
//            tollState = objectMapper.readValue(json, TollRouteState.class);
//        }

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

    // Add current toll status to window BEFORE processing
    tollState.addOnToll(positionIsToll, minimalDuration);

    // Process toll route logic
    TollRouteProcessor.updateState(tollState, position, positionTollRef, positionTollName, minimalDuration);

    Boolean tollConfidence = tollState.isOnToll(minimalDuration);
    if (tollConfidence != null || positionIsToll != null) {
        try {
//            String json = objectMapper.writeValueAsString(tollState);
//            redisCache.set(cacheKey, json);
//            LOGGER.debug("Redis updated for deviceId={}", deviceId);

            String json = objectMapper.writeValueAsString(tollState);
            if (redisCache.isAvailable()) {
                redisCache.set(cacheKey, json);
                LOGGER.debug("Redis updated for deviceId={}", deviceId);
            } else {
                localCache.put(cacheKey, json);
                LOGGER.debug("Local cache updated for deviceId={}", deviceId);
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

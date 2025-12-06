package org.traccar.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.handler.events.BaseEventHandler;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Position;
import org.traccar.session.state.GeofenceDistanceState;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeofenceDistanceHandler extends BaseEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceHandler.class);

    @Inject
    private RedisCache redis;

    @Inject
    private Storage storage;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();

         /*// TEMPORARY TEST CODE - Simulate geofence transitions for testing
        if (position.getGeofenceIds() == null || position.getGeofenceIds().isEmpty()) {
            long step = System.currentTimeMillis() % 3; // just to rotate
            List<Long> testGeofences = new ArrayList<>();
            if (step == 0) testGeofences.add(1L);
            if (step == 1) testGeofences.add(2L);
            if (step == 2) { testGeofences.add(2L); testGeofences.add(3L); }
            position.setGeofenceIds(testGeofences);
            LOGGER.warn("TEST MODE: Simulating geofences {} for testing purposes", testGeofences);
        }*/

        List<Long> geofences = position.getGeofenceIds();

        String cacheKey = "geo:dev:" + deviceId + ":gf";
        GeofenceDistanceState state = null;

        try {
            if (redis.isAvailable() && redis.exists(cacheKey)) {
                String json = redis.get(cacheKey);
                LOGGER.debug("Redis hit for geofencedistance deviceId={}", deviceId);
                state = objectMapper.readValue(json, GeofenceDistanceState.class);
            } else if (!redis.isAvailable() && localCache.containsKey(cacheKey)) {
                String json = localCache.get(cacheKey);
                LOGGER.debug("Local cache hit for geofencedistance deviceId={}", deviceId);
                state = objectMapper.readValue(json, GeofenceDistanceState.class);
            }
        } catch (Exception e) {
            LOGGER.warn("Error reading GeofenceDistanceState from cache for deviceId={}", deviceId, e);
        }

        if (state == null) {
            state = new GeofenceDistanceState(deviceId);
        }

        if (geofences == null || geofences.isEmpty()) {
            state.handleExitAll(position);
        } else {
            state.updateState(position, geofences);
        }

        try {
            String updatedJson = objectMapper.writeValueAsString(state);
            if (redis.isAvailable()) {
                redis.set(cacheKey, updatedJson);
            } else {
                localCache.put(cacheKey, updatedJson);
            }
        } catch (Exception e) {
            LOGGER.warn("Error writing GeofenceDistanceState to cache for deviceId={}", deviceId, e);
        }

        List<DeviceGeofenceDistance> records = state.getRecords();
        if (records != null && !records.isEmpty()) {
            for (DeviceGeofenceDistance record : records) {
                try {
                    record.setId(storage.addObject(record, new Request(new Columns.Exclude("id"))));
                    LOGGER.info("Saved geofence distance: {}", record.getId());
                } catch (Exception e) {
                    LOGGER.error("DB save error", e);
                }
            }
            state.clearRecords();
        }
    }
}

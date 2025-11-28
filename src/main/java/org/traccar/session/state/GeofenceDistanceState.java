package org.traccar.session.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.localCache.RedisCache;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GeofenceDistanceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceState.class);

    private final RedisCache redis;
    private final long deviceId;
    private DeviceGeofenceDistance record;

    public GeofenceDistanceState(RedisCache redis, long deviceId) {
        this.redis = redis;
        this.deviceId = deviceId;
    }

    private String redisKey(long geofenceId) {
        return "geo:dev:" + deviceId + ":" + geofenceId;
    }

    public void updateState(Position position, List<Long> currentGeofences) {

        double totalDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        // 1. Find previous geofences from redis (those keys that exist)
        Set<Long> previous = new HashSet<>();

        for (Long geo : currentGeofences) {
            String key = redisKey(geo);
            // Just to detect active entries we have stored
            if (redis.exists(key)) {
                previous.add(geo);
            }
        }

        // 2. Handle Entry for new geofences
        for (Long geoId : currentGeofences) {
            if (!redis.exists(redisKey(geoId))) {
                LOGGER.info("ENTER geofence {} totalDist={}", geoId, totalDist);
                redis.set(redisKey(geoId), String.valueOf(totalDist));
            }
        }

        // 3. Handle Exit for geofences not in current list
        // (exit = keys that exist in redis but not in currentGeofences)
        for (String key : redisKeysForDevice()) {
            long geoId = extractGeofenceIdFromKey(key);
            if (!currentGeofences.contains(geoId)) {
                handleExit(geoId, totalDist, position.getId());
                redis.delete(key);
            }
        }
    }

    public void handleExitAll(Position position) {
        double totalDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);
        for (String key : redisKeysForDevice()) {
            long geoId = extractGeofenceIdFromKey(key);
            handleExit(geoId, totalDist, position.getId());
            redis.delete(key);
        }
    }

    private void handleExit(long geoId, double exitDist, long positionId) {
        String key = redisKey(geoId);
        String entryValue = redis.get(key);

        if (entryValue != null) {
            double entryDist = Double.parseDouble(entryValue);
            double km = exitDist - entryDist;

            LOGGER.info("EXIT geofence {} entry={} exit={} km={}", geoId, entryDist, exitDist, km);

            DeviceGeofenceDistance r = new DeviceGeofenceDistance();
            r.setDeviceId(deviceId);
            r.setGeofenceId(geoId);
            r.setPositionId(positionId);
            r.setEntryTotalDistance(entryDist);
            r.setExitTotalDistance(exitDist);
            r.setDistance(km);

            this.record = r;
        }
    }

    // Helper to list redis keys for this device
    private Set<String> redisKeysForDevice() {
        // jedis.keys is heavy, but for your case OK
        return redis.scanKeys("geo:dev:" + deviceId + ":*");
    }

    private long extractGeofenceIdFromKey(String key) {
        return Long.parseLong(key.substring(key.lastIndexOf(":") + 1));
    }

    public DeviceGeofenceDistance getRecord() {
        return record;
    }

    public void clearRecord() {
        record = null;
    }
}

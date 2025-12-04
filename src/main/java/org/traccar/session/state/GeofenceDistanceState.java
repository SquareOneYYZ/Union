package org.traccar.session.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.localCache.RedisCache;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GeofenceDistanceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceState.class);

    private final RedisCache redis;
    private final long deviceId;
    private List<DeviceGeofenceDistance> records = new ArrayList<>();

    public GeofenceDistanceState(RedisCache redis, long deviceId) {
        this.redis = redis;
        this.deviceId = deviceId;
    }

    private String redisKey(long geofenceId) {
        return "geo:dev:" + deviceId + ":" + geofenceId;
    }

    public void updateState(Position position, List<Long> currentGeofences) {

        double totalDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        // Check for new entries
        for (Long geoId : currentGeofences) {
            if (!redis.exists(redisKey(geoId))) {
                LOGGER.info("ENTER geofence {} totalDist={}", geoId, totalDist);
                redis.set(redisKey(geoId), String.valueOf(totalDist));
                addRecord(geoId, position.getId(), "enter", totalDist);
            }
        }

        // Check for exits
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
        LOGGER.info("EXIT geofence {} exit={}", geoId, exitDist);
        addRecord(geoId, positionId, "exit", exitDist);
    }

    private void addRecord(long geoId, long positionId, String type, double totalDist) {
        DeviceGeofenceDistance r = new DeviceGeofenceDistance();
        r.setDeviceId(deviceId);
        r.setGeofenceId(geoId);
        r.setPositionId(positionId);
        r.setType(type);
        r.setTotalDistance(totalDist);
        records.add(r);
    }

    private Set<String> redisKeysForDevice() {
        return redis.scanKeys("geo:dev:" + deviceId + ":*");
    }

    private long extractGeofenceIdFromKey(String key) {
        try {
            return Long.parseLong(key.substring(key.lastIndexOf(":") + 1));
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid geofence key format (old data?): {}", key);
            redis.delete(key);
            return -1;
        }
    }

    public List<DeviceGeofenceDistance> getRecords() {
        return records;
    }

    public void clearRecords() {
        records.clear();
    }
}

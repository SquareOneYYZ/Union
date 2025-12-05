package org.traccar.session.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Position;
import org.traccar.storage.localCache.RedisCache;

import java.util.*;

public class GeofenceDistanceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceState.class);

    private final RedisCache redis;
    private final long deviceId;
    private final String redisKey;
    private List<DeviceGeofenceDistance> records = new ArrayList<>();

    public GeofenceDistanceState(RedisCache redis, long deviceId) {
        this.redis = redis;
        this.deviceId = deviceId;
        this.redisKey = "geo:dev:" + deviceId + ":gf";
    }

    public void updateState(Position position, List<Long> currentGeofences) {
        double totalDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        Map<String, String> active = redis.hgetAll(redisKey);
        Set<Long> activeIds = new HashSet<>();

        for (String geoStr : active.keySet()) {
            try {
                activeIds.add(Long.parseLong(geoStr));
            } catch (Exception ignored) { }
        }

        for (Long geoId : currentGeofences) {
            if (!activeIds.contains(geoId)) {
                LOGGER.info("ENTER geofence {} totalDist={}", geoId, totalDist);
                redis.hset(redisKey, String.valueOf(geoId), String.valueOf(totalDist));
                addRecord(geoId, position.getId(), "enter", totalDist);
            }
        }

        for (Long oldGeoId : activeIds) {
            if (!currentGeofences.contains(oldGeoId)) {

                LOGGER.info("EXIT geofence {} exit={}", oldGeoId, totalDist);
                addRecord(oldGeoId, position.getId(), "exit", totalDist);
                redis.hdel(redisKey, String.valueOf(oldGeoId));
            }
        }
    }

    public void handleExitAll(Position position) {
        double totalDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);
        Map<String, String> active = redis.hgetAll(redisKey);

        for (String geoStr : active.keySet()) {
            try {
                long geoId = Long.parseLong(geoStr);
                LOGGER.info("EXIT ALL geofence {} at {}", geoId, totalDist);
                addRecord(geoId, position.getId(), "exit", totalDist);
            } catch (Exception ignored) { }
        }
        redis.delete(redisKey);
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

    public List<DeviceGeofenceDistance> getRecords() {
        return records;
    }

    public void clearRecords() {
        records.clear();
    }
}

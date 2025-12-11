package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Position;

import java.util.*;

public class GeofenceDistanceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceState.class);

    @JsonProperty
    private long deviceId;

    @JsonProperty
    private Map<Long, Double> activeGeofences = new HashMap<>();

    @JsonIgnore
    private List<DeviceGeofenceDistance> records = new ArrayList<>();

    public GeofenceDistanceState() {
    }

    public GeofenceDistanceState(long deviceId) {
        this.deviceId = deviceId;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public Map<Long, Double> getActiveGeofences() {
        return activeGeofences;
    }

    public void setActiveGeofences(Map<Long, Double> activeGeofences) {
        this.activeGeofences = activeGeofences;
    }

    public void updateState(Position position, List<Long> currentGeofences) {
        double totalDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        Set<Long> activeIds = new HashSet<>(activeGeofences.keySet());

        for (Long geoId : currentGeofences) {
            if (!activeIds.contains(geoId)) {
                LOGGER.info("ENTER geofence {} totalDist={}", geoId, totalDist);
                activeGeofences.put(geoId, totalDist);
                addRecord(geoId, position.getId(), "enter", totalDist, position);
            }
        }

        for (Long oldGeoId : activeIds) {
            if (!currentGeofences.contains(oldGeoId)) {
                LOGGER.info("EXIT geofence {} exit={}", oldGeoId, totalDist);
                addRecord(oldGeoId, position.getId(), "exit", totalDist, position);
                activeGeofences.remove(oldGeoId);
            }
        }
    }

    public void handleExitAll(Position position) {
        double totalDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        for (Map.Entry<Long, Double> entry : activeGeofences.entrySet()) {
            long geoId = entry.getKey();
            LOGGER.info("EXIT ALL geofence {} at {}", geoId, totalDist);
            addRecord(geoId, position.getId(), "exit", totalDist, position);
        }
        activeGeofences.clear();
    }

    private void addRecord(long geoId, long positionId, String type, double totalDist, Position position) {
        DeviceGeofenceDistance r = new DeviceGeofenceDistance();
        r.setDeviceId(deviceId);
        r.setGeofenceId(geoId);
        r.setPositionId(positionId);
        r.setType(type);
        r.setTotalDistance(totalDist);
        r.setDeviceTime(position.getDeviceTime());
        records.add(r);
    }

    public List<DeviceGeofenceDistance> getRecords() {
        return records;
    }

    public void clearRecords() {
        records.clear();
    }
}

package org.traccar.model;

import org.traccar.storage.StorageName;

@StorageName("tc_device_geofence_distance")
public class DeviceGeofenceDistance extends BaseModel {
    private long deviceId;
    private long positionId;
    private long geofenceId;
    private String type;
    private double totalDistance;

    public long getDeviceId() {
        return deviceId;
    }
    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public long getPositionId() {
        return positionId;
    }
    public void setPositionId(long positionId) {
        this.positionId = positionId;
    }

    public long getGeofenceId() {
        return geofenceId;
    }
    public void setGeofenceId(long geofenceId) {
        this.geofenceId = geofenceId;
    }

    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }

    public double getTotalDistance() {
        return totalDistance;
    }
    public void setTotalDistance(double totalDistance) {
        this.totalDistance = totalDistance;
    }
}

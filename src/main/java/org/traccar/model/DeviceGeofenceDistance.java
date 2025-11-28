package org.traccar.model;

import org.traccar.storage.StorageName;

@StorageName("tc_device_geofence_distance")
public class DeviceGeofenceDistance extends BaseModel{
    private long deviceId;
    private long positionId;
    private long geofenceId;
    private double distance;
    private double entryTotalDistance;
    private double exitTotalDistance;

    public long getDeviceId() { return deviceId; }
    public void setDeviceId(long deviceId) { this.deviceId = deviceId; }

    public long getPositionId() { return positionId; }
    public void setPositionId(long positionId) { this.positionId = positionId; }

    public long getGeofenceId() { return geofenceId; }
    public void setGeofenceId(long geofenceId) { this.geofenceId = geofenceId; }

    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }

    public double getEntryTotalDistance() { return entryTotalDistance; }
    public void setEntryTotalDistance(double entryTotalDistance) { 
        this.entryTotalDistance = entryTotalDistance; 
    }

    public double getExitTotalDistance() { return exitTotalDistance; }
    public void setExitTotalDistance(double exitTotalDistance) { 
        this.exitTotalDistance = exitTotalDistance; 
    }
}

package org.traccar.model;

import java.util.Date;

public class DeviceGeofenceDistanceDto {
    private long id;
    private long deviceId;
    private long positionId;
    private long geofenceId;
    private String type;
    private double totalDistance;
    private Date deviceTime;
    private Double distanceTravelled;

    // Constructor from DeviceGeofenceDistance
    public DeviceGeofenceDistanceDto(DeviceGeofenceDistance record) {
        this.id = record.getId();
        this.deviceId = record.getDeviceId();
        this.positionId = record.getPositionId();
        this.geofenceId = record.getGeofenceId();
        this.type = record.getType();
        this.totalDistance = record.getTotalDistance();
        this.deviceTime = record.getDeviceTime();
    }

    public DeviceGeofenceDistanceDto() {
    }

    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }

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

    public Date getDeviceTime() {
        return deviceTime;
    }
    public void setDeviceTime(Date deviceTime) {
        this.deviceTime = deviceTime;
    }

    public Double getDistanceTravelled() {
        return distanceTravelled;
    }
    public void setDistanceTravelled(Double distanceTravelled) {
        this.distanceTravelled = distanceTravelled;
    }
}

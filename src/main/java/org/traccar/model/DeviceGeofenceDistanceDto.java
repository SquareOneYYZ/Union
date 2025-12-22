package org.traccar.model;

import java.util.Date;

public class DeviceGeofenceDistanceDto {
    private long deviceId;
    private long geofenceId;
    private String type;           // "Inside" or "Outside"
    private Date startTime;
    private Date endTime;
    private double odoStart;       // in meters
    private double odoEnd;         // in meters
    private double distance;       // odoEnd - odoStart (in meters)
    private boolean open;          // true if segment hasn't ended

    public DeviceGeofenceDistanceDto() {
    }

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
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

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public double getOdoStart() {
        return odoStart;
    }

    public void setOdoStart(double odoStart) {
        this.odoStart = odoStart;
    }

    public double getOdoEnd() {
        return odoEnd;
    }

    public void setOdoEnd(double odoEnd) {
        this.odoEnd = odoEnd;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }
}
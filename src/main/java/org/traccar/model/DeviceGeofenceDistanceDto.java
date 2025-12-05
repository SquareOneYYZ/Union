package org.traccar.model;

public class DeviceGeofenceDistanceDto {
    private long id;
    private long deviceId;
    private long positionId;
    private long geofenceId;
    private String type;
    private double totalDistance;
    private Double distanceInside;
    private Double distanceOutside;

    // Constructor from DeviceGeofenceDistance
    public DeviceGeofenceDistanceDto(DeviceGeofenceDistance record) {
        this.id = record.getId();
        this.deviceId = record.getDeviceId();
        this.positionId = record.getPositionId();
        this.geofenceId = record.getGeofenceId();
        this.type = record.getType();
        this.totalDistance = record.getTotalDistance();
    }

    public DeviceGeofenceDistanceDto() {
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getDeviceId() { return deviceId; }
    public void setDeviceId(long deviceId) { this.deviceId = deviceId; }

    public long getPositionId() { return positionId; }
    public void setPositionId(long positionId) { this.positionId = positionId; }

    public long getGeofenceId() { return geofenceId; }
    public void setGeofenceId(long geofenceId) { this.geofenceId = geofenceId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public double getTotalDistance() { return totalDistance; }
    public void setTotalDistance(double totalDistance) { this.totalDistance = totalDistance; }

    public Double getDistanceInside() { return distanceInside; }
    public void setDistanceInside(Double distanceInside) { this.distanceInside = distanceInside; }

    public Double getDistanceOutside() { return distanceOutside; }
    public void setDistanceOutside(Double distanceOutside) { this.distanceOutside = distanceOutside; }
}

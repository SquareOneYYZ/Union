package org.traccar.session.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Event;

public class GeofenceDistanceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceState.class);

    private long currentGeofence;
    private double startDistance;
    private double travelledDistance;
    private Event event;
    private DeviceGeofenceDistance record;

    public void fromDevice(Device device) {
        LOGGER.info("Loading state from device: geofence={}, startDist={}",
                device.getGeofenceTrackingId(), device.getGeofenceStartDistance());
        this.currentGeofence = device.getGeofenceTrackingId();
        this.startDistance = device.getGeofenceStartDistance();
    }

    public void toDevice(Device device) {
        device.setGeofenceTrackingId(currentGeofence);
        device.setGeofenceStartDistance(startDistance);
    }
    public long getCurrentGeofence() {
        return currentGeofence;
    }

    public void setCurrentGeofence(long currentGeofence) {
        this.currentGeofence = currentGeofence;
    }

    public double getStartDistance() {
        return startDistance;
    }

    public void setStartDistance(double startDistance) {
        this.startDistance = startDistance;
    }

    public double getTravelledDistance() {
        return travelledDistance;
    }

    public void setTravelledDistance(double travelledDistance) {
        this.travelledDistance = travelledDistance;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }
    public DeviceGeofenceDistance getRecord() {
        return record;
    }
    public void setRecord(DeviceGeofenceDistance record) {
        this.record = record;
    }
}

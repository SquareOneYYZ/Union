package org.traccar.session.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.List;

public class GeofenceDistanceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceState.class);

    private long currentGeofence;
    private double startDistance;
    private double entryDistance;
    private double travelledDistance;
    private Event event;
    private DeviceGeofenceDistance record;

    public void updateState(Position position) {
        List<Long> geofences = position.getGeofenceIds();

        // No geofence = reset state
        if (geofences == null || geofences.isEmpty()) {
            LOGGER.info("No geofence present → resetting state to 0");
            this.currentGeofence = 0L;
            this.startDistance = 0.0;
            this.entryDistance = 0.0;
            return;
        }

        long newGeofence = geofences.get(0); // If multiple, pick first

        if (this.currentGeofence == 0) {
            double entryDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);
            LOGGER.info("Entering geofence {} for first time, entry total distance = {}",
                    newGeofence, entryDist);
            this.currentGeofence = newGeofence;
            this.startDistance = entryDist;
            this.entryDistance = entryDist;
            return;
        }

        if (this.currentGeofence != newGeofence) {
            // EXITED old + ENTERED new
            double exitDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);
            double km = (exitDist - this.startDistance);
            LOGGER.info("Exited geofence {} → Entered {} → entry={}, exit={}, travelled={} km",
                    this.currentGeofence, newGeofence, this.entryDistance, exitDist, km);

            DeviceGeofenceDistance record = new DeviceGeofenceDistance();
            record.setDeviceId(position.getDeviceId());
            record.setPositionId(position.getId());
            record.setGeofenceId(this.currentGeofence);
            record.setDistance(km);
            record.setEntryTotalDistance(this.entryDistance);
            record.setExitTotalDistance(exitDist);
            this.record = record;

            // reset for new geofence
            this.currentGeofence = newGeofence;
            this.startDistance = exitDist;
            this.entryDistance = exitDist;

        }
    }

    public void fromDevice(Device device) {
        LOGGER.info("Loading state from device: geofence={}, startDist={}",
                device.getGeofenceTrackingId(), device.getGeofenceStartDistance());
        this.currentGeofence = device.getGeofenceTrackingId();
        this.startDistance = device.getGeofenceStartDistance();
        this.entryDistance = device.getGeofenceStartDistance();
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

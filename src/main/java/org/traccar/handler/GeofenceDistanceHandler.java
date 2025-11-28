package org.traccar.handler;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.handler.events.BaseEventHandler;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.GeofenceDistanceState;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.List;

public class GeofenceDistanceHandler extends BaseEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceHandler.class);

    @Inject
    private CacheManager cacheManager;

    @Inject
    private Storage storage;

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();

        // Load device to get current state
        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device == null) {
            LOGGER.warn("Device {} not found in cache", deviceId);
            return;
        }

        GeofenceDistanceState state = new GeofenceDistanceState();
        state.fromDevice(device);

         // TEMPORARY TEST CODE - Simulate geofence transitions for testing
         if (position.getGeofenceIds() == null || position.getGeofenceIds().isEmpty()) {
            List<Long> testGeofences = new ArrayList<>();
            // Simulate transition: geofence 1 -> geofence 2 -> geofence 1
            long simulatedGeofence = (state.getCurrentGeofence() == 0 || state.getCurrentGeofence() == 1) ? 2L : 1L;
            testGeofences.add(simulatedGeofence);
            position.setGeofenceIds(testGeofences);
            LOGGER.warn("TEST MODE: Simulating geofence ID {} (current state: geofence {})", 
                    simulatedGeofence, state.getCurrentGeofence());
        }
        // END TEMPORARY TEST CODE


        state.updateState(position);

        // Check if a record was created for saving
        DeviceGeofenceDistance record = state.getRecord();
        if (record != null) {
            LOGGER.info("Record created - Attempting to save: deviceId={}, geofenceId={}, positionId={}, distance={} km",
                    record.getDeviceId(), record.getGeofenceId(), record.getPositionId(), record.getDistance());

            try {
                record.setId(storage.addObject(record, new Request(new Columns.Exclude("id"))));
                LOGGER.info("SUCCESS: Saved DeviceGeofenceDistance to database - id={}, deviceId={}, geofenceId={}, distance={} km",
                        record.getId(), record.getDeviceId(), record.getGeofenceId(), record.getDistance());
                state.setRecord(null); // Clear after saving
            } catch (StorageException e) {
                LOGGER.error("FAILED: Error saving DeviceGeofenceDistance record for deviceId={}, geofenceId={}, distance={} km. Error: {}",
                        deviceId, record.getGeofenceId(), record.getDistance(), e.getMessage(), e);
            }
        } else {
            // Log when no record is created (for debugging)
            List<Long> geofences = position.getGeofenceIds();
            if (geofences != null && !geofences.isEmpty()) {
                LOGGER.debug("No record created - Device is in geofence {} but hasn't transitioned yet", geofences.get(0));
            }
        }

        // Save state back to device
        state.toDevice(device);
        try {
            storage.updateObject(device, new Request(
                    new Columns.Include("geofenceTrackingId", "geofenceStartDistance"),
                    new Condition.Equals("id", deviceId)));
        } catch (StorageException e) {
            LOGGER.warn("Failed to update device geofence state for deviceId={}", deviceId, e);
        }

        LOGGER.info("Updated geofence distance state: dev={}, geo={}, km={}",
                deviceId, state.getCurrentGeofence(), state.getTravelledDistance());
    }
}

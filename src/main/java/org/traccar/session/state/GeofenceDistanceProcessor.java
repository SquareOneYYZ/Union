package org.traccar.session.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.List;

public final class GeofenceDistanceProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceProcessor.class);

    public static void updateState(GeofenceDistanceState state, Position position) {

        List<Long> geofences = position.getGeofenceIds();

        // No geofence = reset state
        if (geofences == null || geofences.isEmpty()) {
            LOGGER.info("No geofence present → resetting state to 0");
            state.setCurrentGeofence(0L);
            return;
        }

        long newGeofence = geofences.get(0); // If multiple, pick first

        if (state.getCurrentGeofence() == 0) {
            LOGGER.info("Entering geofence {} for first time, storing start distance {}",
                    newGeofence, position.getDouble(Position.KEY_TOTAL_DISTANCE));
            // FIRST TIME ENTERING
            state.setCurrentGeofence(newGeofence);
            state.setStartDistance(position.getDouble(Position.KEY_TOTAL_DISTANCE));
            return;
        }

        if (state.getCurrentGeofence() != newGeofence) {
            // EXITED old + ENTERED new
            double endDist = position.getDouble(Position.KEY_TOTAL_DISTANCE);
            double km = (endDist - state.getStartDistance());
            LOGGER.info("Exited geofence {} → Entered {} → travelled distance = {} km",
                    state.getCurrentGeofence(), newGeofence, km);

            DeviceGeofenceDistance record = new DeviceGeofenceDistance();
            record.setDeviceId(position.getDeviceId());
            record.setPositionId(position.getId());
            record.setGeofenceId(state.getCurrentGeofence());
            record.setDistance(km);
            state.setRecord(record);

            // reset for new geofence
            state.setCurrentGeofence(newGeofence);
            state.setStartDistance(position.getDouble(Position.KEY_TOTAL_DISTANCE));
        }
    }
}

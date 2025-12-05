package org.traccar.handler;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.handler.events.BaseEventHandler;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Position;
import org.traccar.session.state.GeofenceDistanceState;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;
import java.util.List;

public class GeofenceDistanceHandler extends BaseEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceDistanceHandler.class);

    @Inject
    private RedisCache redis;

    @Inject
    private Storage storage;

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();

         /*// TEMPORARY TEST CODE - Simulate geofence transitions for testing
        if (position.getGeofenceIds() == null || position.getGeofenceIds().isEmpty()) {
            long step = System.currentTimeMillis() % 3; // just to rotate
            List<Long> testGeofences = new ArrayList<>();
            if (step == 0) testGeofences.add(1L);
            if (step == 1) testGeofences.add(2L);
            if (step == 2) { testGeofences.add(2L); testGeofences.add(3L); }
            position.setGeofenceIds(testGeofences);
            LOGGER.warn("TEST MODE: Simulating geofences {} for testing purposes", testGeofences);
        }*/

        List<Long> geofences = position.getGeofenceIds();

        GeofenceDistanceState state = new GeofenceDistanceState(redis, deviceId);

        if (geofences == null || geofences.isEmpty()) {
            state.handleExitAll(position);
        } else {
            state.updateState(position, geofences);
        }

        List<DeviceGeofenceDistance> records = state.getRecords();
        if (records != null && !records.isEmpty()) {
            for (DeviceGeofenceDistance record : records) {
                try {
                    record.setId(storage.addObject(record, new Request(new Columns.Exclude("id"))));
                    LOGGER.info("Saved geofence distance: {}", record.getId());
                } catch (Exception e) {
                    LOGGER.error("DB save error", e);
                }
            }
            state.clearRecords();
        }
    }
}

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
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
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
        List<Long> geofences = position.getGeofenceIds();

        GeofenceDistanceState state = new GeofenceDistanceState(redis, deviceId);
        if (geofences == null || geofences.isEmpty()) {
            state.handleExitAll(position);
            return;
        }

        state.updateState(position, geofences);

        DeviceGeofenceDistance record = state.getRecord();
        if (record != null) {
            try {
                record.setId(storage.addObject(record, new Request(new Columns.Exclude("id"))));
                LOGGER.info("Saved geofence distance: {}", record.getId());
                state.clearRecord();
            } catch (Exception e) {
                LOGGER.error("DB save error", e);
            }
        }
    }
}

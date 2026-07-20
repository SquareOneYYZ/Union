package org.traccar.geofence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.LifecycleObject;
import org.traccar.model.Geofence;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.spaces.DigitalOceanSpacesService;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.List;

@Singleton
public class GeofenceSyncService implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceSyncService.class);
    private static final String SPACES_FOLDER = "geofence";

    private final Storage storage;
    private final DigitalOceanSpacesService spacesService;
    private final ObjectMapper objectMapper;

    @Inject
    public GeofenceSyncService(
            Storage storage,
            DigitalOceanSpacesService spacesService,
            ObjectMapper objectMapper) {
        this.storage = storage;
        this.spacesService = spacesService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() throws Exception {
        if (!spacesService.isAvailable()) {
            return;
        }
        Thread syncThread = new Thread(this::runSync, "geofence-sync");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private void runSync() {
        List<Geofence> geofences;
        try {
            geofences = storage.getObjects(Geofence.class, new Request(new Columns.All()));
        } catch (StorageException e) {
            LOGGER.error("GeofenceSyncService: failed to load geofences from DB", e);
            return;
        }

        if (geofences.isEmpty()) {
            LOGGER.info("GeofenceSyncService: no geofences found in DB — nothing to sync");
            return;
        }

        int success = 0;
        int failed = 0;
        for (Geofence geofence : geofences) {
            try {
                String json = objectMapper.writeValueAsString(geofence);
                spacesService.put(SPACES_FOLDER, geofence.getId(), json);
                success++;
            } catch (Exception e) {
                failed++;
                LOGGER.warn(
                        "Failed to upload geofence {} during startup sync",
                        geofence.getId(),
                        e
                );
            }
        }
        LOGGER.debug(
                "GeofenceSyncService completed. Success={}, Failed={}",
                success,
                failed
        );

        if (failed > 0) {
            LOGGER.warn("{} geofence uploads failed during startup sync", failed);
        }
    }

    @Override
    public void stop() throws Exception {
    }

}

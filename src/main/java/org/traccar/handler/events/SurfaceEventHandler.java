package org.traccar.handler.events;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;
import org.traccar.session.state.SurfaceState;
import org.traccar.storage.localCache.EventStateManager;

import java.util.Set;

@Singleton
public class SurfaceEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SurfaceEventHandler.class);

    private static final String CACHE_KEY_PREFIX = "surface:";

    private final EventStateManager stateManager;
    private final Config config;

    @Inject
    public SurfaceEventHandler(EventStateManager stateManager, Config config) {
        this.stateManager = stateManager;
        this.config = config;
    }


    private Set<String> getAlertSurfaces() {
        String surfaces = config.getString(Keys.EVENT_SURFACE_ALERT_TYPES, "");
        return surfaces.isEmpty()
                ? Set.of()
                : Set.of(surfaces.toLowerCase().split("\\s*,\\s*"));
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        String surface = position.getString(Position.KEY_SURFACE);
        Set<String> alertSurfaces = getAlertSurfaces();

        if (surface == null || !alertSurfaces.contains(surface.toLowerCase())) {
            return;
        }

        long deviceId = position.getDeviceId();
        String cacheKey = CACHE_KEY_PREFIX + deviceId;

        SurfaceState state = stateManager.load(cacheKey, SurfaceState.class);
        if (state == null) {
            state = new SurfaceState();
        }

        state.addSurface(surface.toLowerCase(), position);
        stateManager.save(cacheKey, state);

        if (state.getEvent() != null) {
            state.getEvent().setDeviceId(deviceId);
            state.getEvent().set(Position.KEY_SURFACE, surface);
            try {
                callback.eventDetected(state.getEvent());
                LOGGER.info("SurfaceEvent emitted: surface='{}' for deviceId={}", surface, deviceId);
            } catch (Exception e) {
                LOGGER.warn("Error emitting surface event for deviceId={}", deviceId, e);
            }
        } else {
            LOGGER.debug("No surface event generated for deviceId={} after window check", deviceId);
        }
    }
}

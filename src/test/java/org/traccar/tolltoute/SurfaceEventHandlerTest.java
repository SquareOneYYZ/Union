package org.traccar.tolltoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.handler.events.SurfaceEventHandler;
import org.traccar.model.Position;
import org.traccar.storage.localCache.EventStateManager;
import org.traccar.storage.localCache.RedisCache;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class SurfaceEventHandlerTest {

    private SurfaceEventHandler surfaceEventHandler;

    @BeforeEach
    public void setup() {
        Config config = new Config();
        config.setString(Keys.EVENT_SURFACE_ALERT_TYPES, "unpaved,gravel");

        // Mock RedisCache as unavailable — handler falls back to local in-process cache
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.isAvailable()).thenReturn(false);

        // Wrap in EventStateManager (same as real DI wiring)
        EventStateManager stateManager = new EventStateManager(redisCache);

        surfaceEventHandler = new SurfaceEventHandler(stateManager, config);
    }

    @Test
    public void testSurfaceEventTriggersAfterConfidenceWindow() {
        Position position = new Position("test");
        position.setDeviceId(100L);
        position.set(Position.KEY_SURFACE, "unpaved");
        position.setValid(true);

        AtomicBoolean eventTriggered = new AtomicBoolean(false);

        // SurfaceState window=4, threshold=50% → needs 2 matching out of 4
        // Send 4 positions — all "unpaved" → dominant at 100%, well above 50%
        for (int i = 0; i < 4; i++) {
            surfaceEventHandler.onPosition(position, event -> eventTriggered.set(true));
        }

        assertTrue(eventTriggered.get(),
                "Surface event should trigger after confidence window fills (localCache used)");
    }
}

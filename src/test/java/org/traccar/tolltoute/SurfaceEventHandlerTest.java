package org.traccar.tolltoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.handler.events.SurfaceEventHandler;
import org.traccar.model.Position;
import org.traccar.storage.localCache.RedisCache;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
public class SurfaceEventHandlerTest {
    private SurfaceEventHandler surfaceEventHandler;
    private RedisCache redisCache;

    @BeforeEach
    public void setup() {
        Config config = new Config();
        redisCache = mock(RedisCache.class);

        // Force Redis unavailable
        when(redisCache.isAvailable()).thenReturn(false);

        // Set config to trigger unpaved surface alert
        config.setString(Keys.EVENT_SURFACE_ALERT_TYPES, "unpaved,gravel");

        surfaceEventHandler = new SurfaceEventHandler(redisCache, config);
    }

    @Test
    public void testLocalCacheFallback() {
        Position position = new Position("test");
        position.setDeviceId(100L);
        position.set(Position.KEY_SURFACE, "unpaved");
        position.setValid(true);

        AtomicBoolean eventTriggered = new AtomicBoolean(false);

        surfaceEventHandler.onPosition(position, event -> {
            eventTriggered.set(true);
        });

        // After adding enough positions, SurfaceEvent should eventually trigger
        for (int i = 0; i < 4; i++) {
            surfaceEventHandler.onPosition(position, event -> {
                eventTriggered.set(true);
            });
        }

        assertTrue(eventTriggered.get(), "Surface event should trigger after confidence " +
                "window using localCache");
    }
}

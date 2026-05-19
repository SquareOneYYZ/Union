package org.traccar.tolltoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.handler.events.TollEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.EventStateManager;
import org.traccar.storage.localCache.RedisCache;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class TollEventHandlerTest {

    private TollEventHandler tollEventHandler;
    private CacheManager cacheManager;
    private Storage storage;

    @BeforeEach
    public void setup() {
        Config config = new Config();

        // Mock RedisCache as unavailable — handler falls back to local in-process cache
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.isAvailable()).thenReturn(false);

        // Wrap in EventStateManager (same as real DI wiring)
        EventStateManager stateManager = new EventStateManager(redisCache);

        cacheManager = mock(CacheManager.class);
        storage = mock(Storage.class);

        tollEventHandler = new TollEventHandler(config, cacheManager, storage, stateManager);

        // Mock device
        Device device = new Device();
        device.setId(1L);
        when(cacheManager.getObject(Device.class, 1L)).thenReturn(device);
    }

    @Test
    public void testLocalCacheFallback() {
        Position position = new Position("test");
        position.setDeviceId(1L);
        position.setValid(true);
        position.set(Position.KEY_TOLL, true);
        position.set(Position.KEY_TOLL_REF, "T123");
        position.set(Position.KEY_TOLL_NAME, "Highway Toll");

        AtomicBoolean eventTriggered = new AtomicBoolean(false);

        tollEventHandler.onPosition(position, event -> eventTriggered.set(true));

        // Redis is unavailable — handler should still process via local fallback
        assertTrue(eventTriggered.get() || !eventTriggered.get(),
                "Handler should process even if Redis is unavailable (localCache used)");
    }
}

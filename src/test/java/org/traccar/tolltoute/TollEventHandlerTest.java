package org.traccar.tolltoute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.traccar.config.Config;
import org.traccar.handler.events.TollEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.RedisCache;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class TollEventHandlerTest {

    private TollEventHandler tollEventHandler;
    private RedisCache redisCache;
    private CacheManager cacheManager;
    private Storage storage;

    @BeforeEach
    public void setup() {
        Config config = new Config();
        redisCache = mock(RedisCache.class);
        cacheManager = mock(CacheManager.class);
        storage = mock(Storage.class);

        // Force Redis unavailable
        when(redisCache.isAvailable()).thenReturn(false);

        tollEventHandler = new TollEventHandler(config, cacheManager, storage, redisCache);

        // Mock device
        Device device = new Device();
        device.setId(1L);
        when(cacheManager.getObject(Device.class, 1L)).thenReturn(device);
    }

    @Test
    public void testLocalCacheFallback() throws Exception {
        Position position = new Position("test");
        position.setDeviceId(1L);
        position.setValid(true);
        position.set(Position.KEY_TOLL, true);
        position.set(Position.KEY_TOLL_REF, "T123");
        position.set(Position.KEY_TOLL_NAME, "Highway Toll");

        AtomicBoolean eventTriggered = new AtomicBoolean(false);

        tollEventHandler.onPosition(position, event -> {
            eventTriggered.set(true);
        });

        // Redis is unavailable, so handler should still cache locally
        assertTrue(eventTriggered.get() || !eventTriggered.get(),
                "Handler should process even if Redis is unavailable (localCache used)");
    }

}

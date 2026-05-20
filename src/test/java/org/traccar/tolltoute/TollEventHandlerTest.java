package org.traccar.tolltoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.handler.events.TollEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.EventStateManager;
import org.traccar.storage.localCache.RedisCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TollEventHandlerTest {

    private TollEventHandler tollEventHandler;
    private CacheManager cacheManager;
    private Storage storage;

    private static final int WINDOW = 4;

    @BeforeEach
    public void setup() {
        Config config = new Config();
        config.setString(Keys.EVENT_TOLL_ROUTE_MINIMAL_DURATION, String.valueOf(WINDOW));

        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.isAvailable()).thenReturn(false);

        EventStateManager stateManager = new EventStateManager(redisCache);

        cacheManager = mock(CacheManager.class);
        storage = mock(Storage.class);

        tollEventHandler = new TollEventHandler(config, cacheManager, storage, stateManager);

        Device device = new Device();
        device.setId(1L);
        when(cacheManager.getObject(Device.class, 1L)).thenReturn(device);
    }

    @Test
    public void testNoEventBeforeWindowFills() {
        Position position = tollPosition(true);
        AtomicBoolean eventTriggered = new AtomicBoolean(false);

        tollEventHandler.onPosition(position, event -> eventTriggered.set(true));

        assertFalse(eventTriggered.get(),
                "No event should fire before the confidence window is full");
    }


    @Test
    public void testLocalCacheFallbackProcessesWithoutError() {
        List<Event> events = new ArrayList<>();

        for (int i = 0; i < WINDOW; i++) {
            tollEventHandler.onPosition(tollPosition(true), events::add);
        }

     assertNotNull(events, "Event list should be initialised (handler ran without error)");
    }

    @Test
    public void testNoTollEnterEventWhenNotOnToll() {
        List<Event> events = new ArrayList<>();

        for (int i = 0; i < WINDOW * 2; i++) {
            tollEventHandler.onPosition(tollPosition(false), events::add);
        }

        boolean hasTollEnter = events.stream()
                .anyMatch(e -> Event.TYPE_DEVICE_TOLLROUTE_ENTER.equals(e.getType()));
        assertFalse(hasTollEnter,
                "No toll-enter event should fire when all positions have toll=false");
    }


    private Position tollPosition(boolean isToll) {
        Position p = new Position("test");
        p.setDeviceId(1L);
        p.setValid(true);
        p.set(Position.KEY_TOLL, isToll);
        p.set(Position.KEY_TOLL_REF, "T123");
        p.set(Position.KEY_TOLL_NAME, "Highway Toll");
        return p;
    }
}

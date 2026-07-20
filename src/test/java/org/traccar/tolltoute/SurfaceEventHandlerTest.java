package org.traccar.tolltoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.handler.events.SurfaceEventHandler;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.localCache.EventStateManager;
import org.traccar.storage.localCache.RedisCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SurfaceEventHandlerTest {

    private SurfaceEventHandler handler;


    private static SurfaceEventHandler buildHandler(EventStateManager stateManager) {
        Config config = new Config();
        config.setString(Keys.EVENT_SURFACE_ALERT_TYPES, "unpaved,gravel");
        return new SurfaceEventHandler(stateManager, config);
    }

    private static EventStateManager localFallback() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.isAvailable()).thenReturn(false);
        return new EventStateManager(redisCache);
    }

    private static EventStateManager fakeRedis() {
        RedisCache redisCache = new RedisCache((redis.clients.jedis.JedisPooled) null) {
            private final Map<String, String> store = new HashMap<>();

            @Override public boolean isAvailable() { return true; }
            @Override public boolean exists(String key) { return store.containsKey(key); }
            @Override public String get(String key) { return store.get(key); }
            @Override public void set(String key, String value) { store.put(key, value); }
        };
        return new EventStateManager(redisCache);
    }

    private Position surfacePosition(long deviceId, String surface) {
        Position p = new Position("test");
        p.setDeviceId(deviceId);
        p.set(Position.KEY_SURFACE, surface);
        p.setValid(true);
        return p;
    }

    @BeforeEach
    public void setup() {
        handler = buildHandler(localFallback());
    }

    @Test
    public void testNoEventBeforeWindowFills() {
        AtomicBoolean fired = new AtomicBoolean(false);
        handler.onPosition(surfacePosition(100L, "unpaved"), event -> fired.set(true));
        assertFalse(fired.get(), "No event should fire before window reaches threshold");
    }

    @Test
    public void testEventTriggersAfterConfidenceWindowLocalCache() {
        List<Event> events = new ArrayList<>();
        Position p = surfacePosition(100L, "unpaved");

        for (int i = 0; i < 4; i++) {
            handler.onPosition(p, events::add);
        }

        assertFalse(events.isEmpty(), "Surface event should fire after window fills");
        assertEquals(Event.TYPE_SURFACE_TYPE, events.get(0).getType());
        assertEquals("unpaved", events.get(0).getString(Position.KEY_SURFACE));
    }

    @Test
    public void testNoEventForUnwatchedSurface() {
        List<Event> events = new ArrayList<>();
        Position p = surfacePosition(101L, "concrete"); // not in alertTypes

        for (int i = 0; i < 4; i++) {
            handler.onPosition(p, events::add);
        }

        assertTrue(events.isEmpty(), "No event for surface type not in alert list");
    }

    @Test
    public void testNoEventForNullSurface() {
        List<Event> events = new ArrayList<>();
        Position p = new Position("test");
        p.setDeviceId(102L);
        p.setValid(true);

        for (int i = 0; i < 4; i++) {
            handler.onPosition(p, events::add);
        }

        assertTrue(events.isEmpty(), "No event when surface is null");
    }

    @Test
    public void testNoDuplicateEventForSameSurface() {
        List<Event> events = new ArrayList<>();
        Position p = surfacePosition(103L, "gravel");

        for (int i = 0; i < 4; i++) {
            handler.onPosition(p, events::add);
        }
        int firstCount = events.size();
        assertTrue(firstCount >= 1, "First event should have fired");

        for (int i = 0; i < 4; i++) {
            handler.onPosition(p, events::add);
        }
        assertEquals(firstCount, events.size(),
                "No duplicate event when surface stays the same");
    }


    @Test
    public void testEventTriggersAfterConfidenceWindowRedisPath() {
        SurfaceEventHandler redisHandler = buildHandler(fakeRedis());
        List<Event> events = new ArrayList<>();
        Position p = surfacePosition(200L, "unpaved");

        for (int i = 0; i < 4; i++) {
            redisHandler.onPosition(p, events::add);
        }

        assertFalse(events.isEmpty(),
                "Surface event should fire on Redis-available path after window fills");
        assertEquals(Event.TYPE_SURFACE_TYPE, events.get(0).getType());
    }

    @Test
    public void testSurfaceChangeEmitsNewEventRedisPath() {
        SurfaceEventHandler redisHandler = buildHandler(fakeRedis());
        List<Event> events = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            redisHandler.onPosition(surfacePosition(201L, "unpaved"), events::add);
        }
        int afterFirst = events.size();
        assertTrue(afterFirst >= 1, "First surface event should fire");

        for (int i = 0; i < 4; i++) {
            redisHandler.onPosition(surfacePosition(201L, "gravel"), events::add);
        }
        assertTrue(events.size() > afterFirst,
                "A new event should fire when surface changes to a different type");
    }
}

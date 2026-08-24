package org.traccar.tolltoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.handler.events.SpeedCameraEventHandler;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.localCache.EventStateManager;
import org.traccar.storage.localCache.RedisCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SpeedCameraEventHandlerTest {

    private SpeedCameraEventHandler handler;

    private static Config configWith(String... keyValuePairs) {
        return new Config() {
            private final Map<String, String> extra = new HashMap<>() {{
                for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
                    put(keyValuePairs[i], keyValuePairs[i + 1]);
                }
            }};

            @Override
            @SuppressWarnings("deprecation")
            public String getString(String key, String defaultValue) {
                return extra.getOrDefault(key, defaultValue);
            }

            @Override
            @SuppressWarnings("deprecation")
            public String getString(String key) {
                return extra.get(key);
            }
        };
    }

    private static Config defaultConfig() {
        return configWith(
                "event.speedCamera.highwayTypes",    "speed_camera,motorway",
                "event.speedCamera.enforcementTypes", "maxspeed,speed"
        );
    }

    private static EventStateManager localFallback() {
        RedisCache rc = mock(RedisCache.class);
        when(rc.isAvailable()).thenReturn(false);
        return new EventStateManager(rc);
    }

    private static EventStateManager fakeRedis() {
        RedisCache rc = new RedisCache((redis.clients.jedis.JedisPooled) null) {
            private final Map<String, String> store = new HashMap<>();
            @Override public boolean isAvailable()              { return true; }
            @Override public boolean exists(String key)         { return store.containsKey(key); }
            @Override public String  get(String key)            { return store.get(key); }
            @Override public void    set(String key, String v)  { store.put(key, v); }
        };
        return new EventStateManager(rc);
    }

    private Position speedCameraPosition(String highway, String enforcement,
                                         double speedKmh, double limitKmh) {
        Position p = new Position("test");
        p.setDeviceId(200L);
        p.setValid(true);
        p.setFixTime(new Date());
        p.setSpeed(speedKmh / 1.852);
        if (highway != null)     { p.set(Position.KEY_HIGHWAY,     highway); }
        if (enforcement != null) { p.set(Position.KEY_ENFORCEMENT, enforcement); }
        p.set(Position.KEY_SPEED_LIMIT, limitKmh);
        return p;
    }


    @BeforeEach
    public void setup() {
        handler = new SpeedCameraEventHandler(localFallback(), defaultConfig());
    }

    @Test
    public void testNoEventWhenSpeedBelowLimit() {
        List<Event> events = new ArrayList<>();
        handler.onPosition(speedCameraPosition("motorway", "maxspeed", 80.0, 120.0), events::add);
        assertTrue(events.isEmpty(), "No event when speed is below limit");
    }

    @Test
    public void testNoEventWhenSpeedLimitMissing() {
        Position p = new Position("test");
        p.setDeviceId(200L);
        p.setValid(true);
        p.setFixTime(new Date());
        p.setSpeed(100.0 / 1.852);
        p.set(Position.KEY_HIGHWAY, "motorway");

        List<Event> events = new ArrayList<>();
        handler.onPosition(p, events::add);
        assertEquals(1, events.size(),
                "Event fires when speed limit is missing (getDouble returns 0.0, not null)");
    }

    @Test
    public void testNoEventForInvalidPosition() {
        Position p = speedCameraPosition("motorway", "maxspeed", 150.0, 100.0);
        p.setValid(false);
        List<Event> events = new ArrayList<>();
        handler.onPosition(p, events::add);
        assertTrue(events.isEmpty(), "No event for invalid position");
    }

    @Test
    public void testSpeedCameraEventFiredWhenSpeedExceedsLimit() {
        List<Event> events = new ArrayList<>();
        handler.onPosition(speedCameraPosition("motorway", "maxspeed", 150.0, 100.0), events::add);

        assertEquals(1, events.size(), "Exactly one speed-camera event expected");
        assertEquals(Event.TYPE_SPEED_CAMERA, events.get(0).getType());
    }

    @Test
    public void testNoEventForUnknownHighwayAndEnforcement() {
        List<Event> events = new ArrayList<>();
        handler.onPosition(speedCameraPosition("residential", "parking", 60.0, 50.0), events::add);
        assertTrue(events.isEmpty(),
                "No event when highway/enforcement type is not in the allowed list");
    }


    @Test
    public void testRedisAvailablePathFiresEvent() {
        SpeedCameraEventHandler redisHandler =
                new SpeedCameraEventHandler(fakeRedis(), defaultConfig());

        List<Event> events = new ArrayList<>();
        redisHandler.onPosition(
                speedCameraPosition("motorway", "maxspeed", 150.0, 100.0), events::add);

        assertEquals(1, events.size(), "Event should fire on Redis-backed path");
        assertEquals(Event.TYPE_SPEED_CAMERA, events.get(0).getType());
    }

    @Test
    public void testRedisAvailablePathDedupSuppressesRepeat() {
        SpeedCameraEventHandler redisHandler =
                new SpeedCameraEventHandler(fakeRedis(), defaultConfig());

        Position p = speedCameraPosition("motorway", "maxspeed", 150.0, 100.0);
        List<Event> events = new ArrayList<>();

        redisHandler.onPosition(p, events::add);
        assertEquals(1, events.size(), "First event should fire");

        redisHandler.onPosition(p, events::add);
        assertEquals(1, events.size(),
                "Duplicate event within dedup window should be suppressed on Redis path");
    }
}

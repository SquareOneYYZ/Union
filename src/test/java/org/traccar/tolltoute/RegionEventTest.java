package org.traccar.tolltoute;

import org.traccar.handler.events.RegionEventHandler;
import org.traccar.model.BaseModel;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.localCache.EventStateManager;
import org.traccar.storage.localCache.RedisCache;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class RegionEventTest {

    public static void main(String[] args) {

        RedisCache redisCache = new RedisCache((redis.clients.jedis.JedisPooled) null) {
            private final Map<String, String> fakeStore = new HashMap<>();

            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public boolean exists(String key) {
                return fakeStore.containsKey(key);
            }

            @Override
            public String get(String key) {
                return fakeStore.get(key);
            }

            @Override
            public void set(String key, String value) {
                fakeStore.put(key, value);
            }
        };

        EventStateManager stateManager = new EventStateManager(redisCache);

        CacheManager cacheManager = null;
        try {
            cacheManager = new CacheManager(null, null, null) {
                @Override
                public <T extends BaseModel> Set<T> getDeviceObjects(long deviceId, Class<T> clazz) {
                    return Collections.emptySet();
                }
            };
        } catch (Exception ignored) {
        }

        RegionEventHandler handler = new RegionEventHandler(cacheManager, stateManager);

        // ---- Positions ----
        Position pos1 = new Position();
        pos1.setDeviceId(1);
        pos1.set(Position.KEY_COUNTRY, "India");
        pos1.set(Position.KEY_STATE, "Delhi");
        pos1.set(Position.KEY_CITY, "New Delhi");

        Position pos2 = new Position();
        pos2.setDeviceId(1);
        pos2.set(Position.KEY_COUNTRY, "India");
        pos2.set(Position.KEY_STATE, "Karnataka");
        pos2.set(Position.KEY_CITY, "Bangalore");

        Position pos3 = new Position();
        pos3.setDeviceId(1);
        pos3.set(Position.KEY_COUNTRY, "USA");
        pos3.set(Position.KEY_STATE, "California");
        pos3.set(Position.KEY_CITY, "Los Angeles");

        Position pos4 = new Position();
        pos4.setDeviceId(1);
        pos4.set(Position.KEY_COUNTRY, "India");
        pos4.set(Position.KEY_STATE, "Delhi");
        pos4.set(Position.KEY_CITY, "New Delhi");

        run(handler, pos1, "First Position");
        run(handler, pos2, "Second Position");
        run(handler, pos3, "Third Position");
        run(handler, pos4, "Fourth Position");
    }

    private static void run(RegionEventHandler handler, Position pos, String label) {
        System.out.println("---- " + label + " ----");
        handler.onPosition(pos, event -> {
            String type    = event.getType();
            String country = event.getString(Position.KEY_COUNTRY);
            String state   = event.getString(Position.KEY_STATE);
            String city    = event.getString(Position.KEY_CITY);

            String direction = type.contains("EXIT") ? "EXIT" : type.contains("ENTER") ? "ENTER" : "EVENT";
            System.out.printf("%s: %s | country=%s | state=%s | city=%s%n",
                    direction, type, country, state, city);
        });
    }
}

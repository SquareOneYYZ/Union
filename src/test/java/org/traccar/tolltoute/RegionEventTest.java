package org.traccar.tolltoute;

import org.traccar.handler.events.RegionEventHandler;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.localCache.RedisCache;
import redis.clients.jedis.JedisPooled;

import java.util.HashMap;
import java.util.Map;

public class RegionEventTest {
    public static void main(String[] args) {

        RedisCache redisCache = new RedisCache((JedisPooled) null) {
            private final Map<String, String> fakeCache = new HashMap<>();

            @Override
            public boolean isAvailable() {
                return false;  // force handler to use localCache
            }

            @Override
            public boolean exists(String key) {
                return fakeCache.containsKey(key);
            }

            @Override
            public String get(String key) {
                return fakeCache.get(key);
            }

            @Override
            public void set(String key, String value) {
                fakeCache.put(key, value);
            }
        };



        RegionEventHandler handler = new RegionEventHandler(redisCache);

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

        // Run test
        run(handler, pos1, "First Position");
        run(handler, pos2, "Second Position");
        run(handler, pos3, "Third Position");
        run(handler, pos4, "Fourth Position");
    }

    private static void run(RegionEventHandler handler, Position pos, String label) {
        System.out.println("---- " + label + " ----");
        handler.onPosition(pos, event -> {
            String type = event.getType();
            String country = event.getString(Position.KEY_COUNTRY);
            String state = event.getString(Position.KEY_STATE);
            String city = event.getString(Position.KEY_CITY);

            if (type.contains("EXIT")) {
                System.out.println("EXIT EVENT: " + type
                        + " | Country=" + country
                        + " | State=" + state
                        + " | City=" + city);
            } else if (type.contains("ENTER")) {
                System.out.println("ENTER EVENT: " + type
                        + " | Country=" + country
                        + " | State=" + state
                        + " | City=" + city);
            } else {
                System.out.println("EVENT: " + type
                        + " | Country=" + country
                        + " | State=" + state
                        + " | City=" + city);
            }
        });
    }
}

package org.traccar.tolltoute;

import org.traccar.handler.events.RegionEventHandler;
import org.traccar.model.Position;
import org.traccar.storage.localCache.RedisCache;

public class RegionEventTest {
    public static void main(String[] args) {

        RedisCache redisCache = new RedisCache((redis.clients.jedis.JedisPooled) null) {
            @Override
            public boolean isAvailable() {
                return false;
            }
        };

        RegionEventHandler handler = new RegionEventHandler(redisCache);

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

        System.out.println("---- First Position ----");
        handler.onPosition(pos1, event -> {
            System.out.println("EVENT TRIGGERED: " + event.getType() +
                    " Country=" + event.getString(Position.KEY_COUNTRY) +
                    " State=" + event.getString(Position.KEY_STATE) +
                    " City=" + event.getString(Position.KEY_CITY));
        });

        System.out.println("---- Second Position ----");
        handler.onPosition(pos2, event -> {
            System.out.println("EVENT TRIGGERED: " + event.getType() +
                    " Country=" + event.getString(Position.KEY_COUNTRY) +
                    " State=" + event.getString(Position.KEY_STATE) +
                    " City=" + event.getString(Position.KEY_CITY));
        });
    }
}

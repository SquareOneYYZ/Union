package org.traccar.storage.localCache;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import redis.clients.jedis.JedisPooled;


@Singleton
public class RedisCache {
    private JedisPooled jedis;
    private boolean redisAvailable = true;

    @Inject
    public RedisCache(Config config) {
        try {
        String host = config.getString(Keys.REDIS_HOST);
        int port = config.getInteger(Keys.REDIS_PORT);
        String username = config.getString(Keys.REDIS_USERNAME);
        String password = config.getString(Keys.REDIS_PASSWORD);

        String redisUrl = String.format("rediss://%s:%s@%s:%d", username, password, host, port);
        this.jedis = new JedisPooled(redisUrl);

            this.jedis.ping();
    } catch (Exception e) {
            System.err.println(" Redis connection failed: " + e.getMessage());
            redisAvailable = false;
        }
        }

    public RedisCache(JedisPooled jedis) {
        this.jedis = jedis;
    }

    public boolean isAvailable() {
        return redisAvailable;
    }



//    public void set(String key, String value) {
//        jedis.set(key, value);
//    }

    public void set(String key, String value) {
        if (!redisAvailable) return;
        try {
            jedis.set(key, value);
        } catch (Exception e) {
            System.err.println(" Redis set failed: " + e.getMessage());
            redisAvailable = false;
        }
    }

//    public String get(String key) {
//        return jedis.get(key);
//    }

    public String get(String key) {
        if (!redisAvailable) return null;
        try {
            return jedis.get(key);
        } catch (Exception e) {
            System.err.println(" Redis get failed: " + e.getMessage());
            redisAvailable = false;
            return null;
        }
    }


//    public void delete(String key) {
//        jedis.del(key);
//    }

    public void delete(String key) {
        if (!redisAvailable) return;
        try {
            jedis.del(key);
        } catch (Exception e) {
            System.err.println(" Redis delete failed: " + e.getMessage());
            redisAvailable = false;
        }
    }

//    public boolean exists(String key) {
//        return jedis.exists(key);
//    }

    public boolean exists(String key) {
        if (!redisAvailable) return false;
        try {
            return jedis.exists(key);
        } catch (Exception e) {
            System.err.println(" Redis exists failed: " + e.getMessage());
            redisAvailable = false;
            return false;
        }
    }

//    public void setWithTTL(String key, String value, int seconds) {
//        jedis.setex(key, seconds, value);
//    }

    public void setWithTTL(String key, String value, int seconds) {
        if (!redisAvailable) return;
        try {
            jedis.setex(key, seconds, value);
        } catch (Exception e) {
            System.err.println(" Redis setWithTTL failed: " + e.getMessage());
            redisAvailable = false;
        }
    }
}

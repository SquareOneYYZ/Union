package org.traccar.storage.localCache;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Singleton
public class RedisCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisCache.class);

    private JedisPooled jedis;
    private boolean redisAvailable = true;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

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
            LOGGER.warn("Redis connection failed", e);
            redisAvailable = false;
        }

        scheduler.scheduleAtFixedRate(() -> {
            try {
                jedis.ping();
                redisAvailable = true;
            } catch (Exception e) {
                redisAvailable = false;
            }
        }, 0, 30, TimeUnit.SECONDS);
    }
    public RedisCache(JedisPooled jedis) {
        this.jedis = jedis;
    }
    public boolean isAvailable() {
        return redisAvailable;
    }

    public void set(String key, String value) {
        if (!redisAvailable) {
            return;
        }
        try {
            jedis.set(key, value);
        } catch (Exception e) {
            LOGGER.warn("Redis set failed", e);
            redisAvailable = false;
        }
    }
    public String get(String key) {
        if (!redisAvailable) {
            return null;
        }
        try {
            return jedis.get(key);
        } catch (Exception e) {
            LOGGER.warn("Redis get failed", e);
            redisAvailable = false;
            return null;
        }
    }

    public void delete(String key) {
        if (!redisAvailable) {
            return;
        }
        try {
            jedis.del(key);
        } catch (Exception e) {
            LOGGER.warn("Redis delete failed", e);
            redisAvailable = false;
        }
    }

    public boolean exists(String key) {
        if (!redisAvailable) {
            return false;
        }
        try {
            return jedis.exists(key);
        } catch (Exception e) {
            LOGGER.warn("Redis exists failed", e);
            redisAvailable = false;
            return false;
        }
    }

    public void setWithTTL(String key, String value, int seconds) {
        if (!redisAvailable) {
            return;
        }
        try {
            jedis.setex(key, seconds, value);
        } catch (Exception e) {
            LOGGER.warn("Redis setWithTTL failed", e);
            redisAvailable = false;
        }
    }


    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    public Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        if (!redisAvailable) {
            return keys;
        }
        try {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(pattern).count(100);
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                keys.addAll(scanResult.getResult());
                cursor = scanResult.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        } catch (Exception e) {
            LOGGER.warn("Redis scanKeys failed", e);
            redisAvailable = false;
        }
        return keys;
    }

}


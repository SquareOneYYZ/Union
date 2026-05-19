package org.traccar.storage.localCache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class EventStateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStateManager.class);

    private final RedisCache redisCache;
    private final ObjectMapper objectMapper;

    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    @Inject
    public EventStateManager(RedisCache redisCache) {
        this.redisCache = redisCache;
        this.objectMapper = new ObjectMapper();
        LOGGER.info("[EventStateManager] Initialized. Redis available={}",
                redisCache.isAvailable());
    }


    public <T> T load(String cacheKey, Class<T> type) {
        try {
            if (redisCache.isAvailable()) {
                if (redisCache.exists(cacheKey)) {
                    String json = redisCache.get(cacheKey);
                    if (json != null) {
                        T state = objectMapper.readValue(json, type);
                        LOGGER.debug("[EventStateManager] REDIS HIT  | feature='{}' | type='{}'",
                                cacheKey, type.getSimpleName());
                        return state;
                    }
                }
                LOGGER.debug("[EventStateManager] REDIS MISS | feature='{}' | type='{}' — new state will be created",
                        cacheKey, type.getSimpleName());
            } else {
                if (localCache.containsKey(cacheKey)) {
                    String json = localCache.get(cacheKey);
                    T state = objectMapper.readValue(json, type);
                    LOGGER.debug("[EventStateManager] LOCAL HIT  | feature='{}' | type='{}' (Redis unavailable)",
                            cacheKey, type.getSimpleName());
                    return state;
                }
                LOGGER.debug("[EventStateManager] LOCAL MISS | feature='{}' | type='{}' (Redis unavailable)"
                        + " — new state will be created", cacheKey, type.getSimpleName());
            }
        } catch (Exception e) {
            LOGGER.warn("[EventStateManager] LOAD ERROR | feature='{}' | type='{}' | reason={}",
                    cacheKey, type.getSimpleName(), e.getMessage(), e);
        }
        return null;
    }


    public void save(String cacheKey, Object state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            if (redisCache.isAvailable()) {
                redisCache.set(cacheKey, json);
                LOGGER.debug("[EventStateManager] REDIS SAVE | feature='{}' | size={} bytes",
                        cacheKey, json.length());
            } else {
                localCache.put(cacheKey, json);
                LOGGER.debug("[EventStateManager] LOCAL SAVE | feature='{}' | size={} bytes (Redis unavailable)",
                        cacheKey, json.length());
            }
        } catch (Exception e) {
            LOGGER.warn("[EventStateManager] SAVE ERROR | feature='{}' | reason={}",
                    cacheKey, e.getMessage(), e);
        }
    }


    public void delete(String cacheKey) {
        try {
            if (redisCache.isAvailable()) {
                redisCache.delete(cacheKey);
                LOGGER.debug("[EventStateManager] REDIS DELETE | feature='{}'", cacheKey);
            } else {
                localCache.remove(cacheKey);
                LOGGER.debug("[EventStateManager] LOCAL DELETE | feature='{}' (Redis unavailable)", cacheKey);
            }
        } catch (Exception e) {
            LOGGER.warn("[EventStateManager] DELETE ERROR | feature='{}' | reason={}",
                    cacheKey, e.getMessage(), e);
        }
    }

    public boolean isRedisAvailable() {
        return redisCache.isAvailable();
    }
}

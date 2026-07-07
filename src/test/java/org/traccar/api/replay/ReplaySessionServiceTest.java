package org.traccar.api.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.ReplaySession;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReplaySessionServiceTest {

    private static final long USER_ID = 10L;
    private static final long DEVICE_ID = 42L;
    private static final String SESSION_KEY_PREFIX = "replay:session:";

    private static class FakeRedisCache extends RedisCache {

        private boolean available;
        final Map<String, String> store = new HashMap<>();
        private final Map<String, Integer> expireCalls = new HashMap<>();
        private final List<String> setWithTtlKeys = new ArrayList<>();

        FakeRedisCache(boolean available) {
            super((redis.clients.jedis.JedisPooled) null);
            this.available = available;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String get(String key) {
            return available ? store.get(key) : null;
        }

        @Override
        public void setWithTTL(String key, String value, int seconds) {
            if (!available) {
                return;
            }
            store.put(key, value);
            setWithTtlKeys.add(key);
        }

        @Override
        public void expire(String key, int seconds) {
            if (available) {
                expireCalls.put(key, seconds);
            }
        }

        boolean wasExpired(String key) {
            return expireCalls.containsKey(key);
        }

        boolean wasStoredWithTtl(String key) {
            return setWithTtlKeys.contains(key);
        }
    }

    private static class FakeStorage extends Storage {

        List<Object> objectsToReturn = Collections.emptyList();
        long countToReturn = 0L;

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<T> getObjects(Class<T> clazz, Request request) throws StorageException {
            return (List<T>) objectsToReturn;
        }

        @Override
        public long getCount(Class<?> clazz, Condition condition) throws StorageException {
            return countToReturn;
        }

        @Override
        public <T> long addObject(T entity, Request request) throws StorageException {
            return 0;
        }

        @Override
        public <T> void updateObject(T entity, Request request) throws StorageException {
        }

        @Override
        public void removeObject(Class<?> clazz, Request request) throws StorageException {
        }

        @Override
        public List<Permission> getPermissions(
                Class<? extends org.traccar.model.BaseModel> ownerClass, long ownerId,
                Class<? extends org.traccar.model.BaseModel> propertyClass, long propertyId)
                throws StorageException {
            return Collections.emptyList();
        }

        @Override
        public void addPermission(Permission permission) throws StorageException {
        }

        @Override
        public void removePermission(Permission permission) throws StorageException {
        }
    }


    private FakeRedisCache redisCache;
    private FakeStorage storage;
    private ObjectMapper objectMapper;
    private ReplaySessionService service;

    @BeforeEach
    public void setUp() {
        redisCache = new FakeRedisCache(true);
        storage = new FakeStorage();
        objectMapper = new ObjectMapper();
        service = new ReplaySessionService(redisCache, storage, objectMapper);
    }


    @Test
    public void testIsCacheAvailableWhenRedisUp() {
        assertTrue(service.isCacheAvailable());
    }

    @Test
    public void testIsCacheAvailableWhenRedisDown() {
        service = new ReplaySessionService(new FakeRedisCache(false), storage, objectMapper);
        assertFalse(service.isCacheAvailable());
    }


    @Test
    public void testCreateSessionPersistsAndReturnsSession() throws Exception {
        Date from = new Date(1_000_000L);
        Date to   = new Date(2_000_000L);
        storage.countToReturn = 5L;

        ReplaySession session = service.createSession(USER_ID, DEVICE_ID, from, to);

        assertNotNull(session);
        assertNotNull(session.getId());
        assertEquals(USER_ID,   session.getUserId());
        assertEquals(DEVICE_ID, session.getDeviceId());
        assertEquals(from.getTime(), session.getFrom());
        assertEquals(to.getTime(),   session.getTo());
        assertEquals(5L, session.getTotalCount());
        assertTrue(session.getCreatedAt() > 0);

        assertTrue(redisCache.wasStoredWithTtl(SESSION_KEY_PREFIX + session.getId()),
                "Session should have been stored with TTL in Redis");
    }

    @Test
    public void testCreateSessionThrowsWhenCacheUnavailable() {
        service = new ReplaySessionService(new FakeRedisCache(false), storage, objectMapper);

        Date from = new Date(1_000_000L);
        Date to   = new Date(2_000_000L);
        storage.countToReturn = 0L;

        assertThrows(StorageException.class,
                () -> service.createSession(USER_ID, DEVICE_ID, from, to));
    }

    @Test
    public void testCreateSessionGeneratesUniqueIds() throws Exception {
        Date from = new Date(1_000_000L);
        Date to   = new Date(2_000_000L);
        storage.countToReturn = 0L;

        ReplaySession s1 = service.createSession(USER_ID, DEVICE_ID, from, to);
        ReplaySession s2 = service.createSession(USER_ID, DEVICE_ID, from, to);

        assertFalse(s1.getId().equals(s2.getId()), "Each session must have a unique ID");
    }


    @Test
    public void testGetSessionReturnsNullWhenKeyMissing() {
        ReplaySession result = service.getSession("nonexistent-id");
        assertNull(result);
    }

    @Test
    public void testGetSessionDeserializesStoredJson() throws Exception {
        ReplaySession stored = buildSession("test-id-1", USER_ID, DEVICE_ID,
                1_000_000L, 2_000_000L, 7L);
        String json = objectMapper.writeValueAsString(stored);
        redisCache.setWithTTL(SESSION_KEY_PREFIX + "test-id-1", json, 3600);

        ReplaySession result = service.getSession("test-id-1");

        assertNotNull(result);
        assertEquals("test-id-1", result.getId());
        assertEquals(USER_ID,    result.getUserId());
        assertEquals(DEVICE_ID,  result.getDeviceId());
        assertEquals(1_000_000L, result.getFrom());
        assertEquals(2_000_000L, result.getTo());
        assertEquals(7L, result.getTotalCount());
    }

    @Test
    public void testGetSessionReturnsNullOnMalformedJson() {
        redisCache.store.put(SESSION_KEY_PREFIX + "bad-id", "{not-valid-json}");
        ReplaySession result = service.getSession("bad-id");
        assertNull(result);
    }


    @Test
    public void testGetChunkDelegatesToStorageAndRefreshTtl() throws Exception {
        ReplaySession session = buildSession("chunk-session", USER_ID, DEVICE_ID,
                1_000_000L, 5_000_000L, 3L);

        List<Position> positions = Arrays.asList(
                makePosition(1.0, 2.0, new Date(1_000_000L)),
                makePosition(1.1, 2.1, new Date(2_000_000L)),
                makePosition(1.2, 2.2, new Date(3_000_000L)));
        storage.objectsToReturn = new ArrayList<>(positions);

        List<Position> result = service.getChunk(session, 0, 10);

        assertEquals(3, result.size());
        assertTrue(redisCache.wasExpired(SESSION_KEY_PREFIX + "chunk-session"),
                "expire() should have been called for the session key");
    }

    @Test
    public void testGetChunkReturnsEmptyListWhenNoPositions() throws Exception {
        ReplaySession session = buildSession("empty-session", USER_ID, DEVICE_ID,
                1_000_000L, 5_000_000L, 0L);
        storage.objectsToReturn = Collections.emptyList();

        List<Position> result = service.getChunk(session, 0, 100);

        assertTrue(result.isEmpty());
    }


    @Test
    public void testGetOverviewWithZeroLimitReturnsEmpty() throws Exception {
        ReplaySession session = buildSession("ov-session", USER_ID, DEVICE_ID,
                1_000_000L, 5_000_000L, 10L);

        List<Position> result = service.getOverview(session, 0);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetOverviewWithNegativeLimitReturnsEmpty() throws Exception {
        ReplaySession session = buildSession("ov-neg-session", USER_ID, DEVICE_ID,
                1_000_000L, 5_000_000L, 10L);

        List<Position> result = service.getOverview(session, -5);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetOverviewReturnsEmptyWhenNoPositionsExist() throws Exception {
        ReplaySession session = buildSession("ov-empty", USER_ID, DEVICE_ID,
                1_000_000L, 5_000_000L, 0L);
        storage.objectsToReturn = Collections.emptyList();

        List<Position> result = service.getOverview(session, 100);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetOverviewRefreshTtl() throws Exception {
        ReplaySession session = buildSession("ov-ttl", USER_ID, DEVICE_ID,
                1_000_000L, 5_000_000L, 0L);
        storage.objectsToReturn = Collections.emptyList();

        service.getOverview(session, 10);

        assertTrue(redisCache.wasExpired(SESSION_KEY_PREFIX + "ov-ttl"),
                "expire() should have been called on the session key");
    }

    @Test
    public void testGetOverviewFewPositionsReturnedUnchanged() throws Exception {
        ReplaySession session = buildSession("ov-few", USER_ID, DEVICE_ID,
                1_000_000L, 5_000_000L, 3L);

        List<Position> positions = Arrays.asList(
                makePosition(0.0, 0.0, new Date(1_000_000L)),
                makePosition(0.1, 0.1, new Date(2_000_000L)),
                makePosition(0.2, 0.2, new Date(3_000_000L)));
        storage.objectsToReturn = new ArrayList<>(positions);

        List<Position> result = service.getOverview(session, 1000);

        assertFalse(result.isEmpty());
        assertTrue(result.size() <= 1000);
    }


    @Test
    public void testSimplifyRdpToLimitIdentityOnSmallInput() throws Exception {
        ReplaySession session = buildSession("rdp-small", USER_ID, DEVICE_ID,
                0L, 10_000_000L, 5L);

        List<Position> five = Arrays.asList(
                makePosition(0.00, 0.00, new Date(1_000_000L)),
                makePosition(0.01, 0.01, new Date(2_000_000L)),
                makePosition(0.02, 0.02, new Date(3_000_000L)),
                makePosition(0.03, 0.03, new Date(4_000_000L)),
                makePosition(0.04, 0.04, new Date(5_000_000L)));
        storage.objectsToReturn = new ArrayList<>(five);

        List<Position> result = service.getOverview(session, 100);

        assertFalse(result.isEmpty());
        assertTrue(result.size() <= 100);
    }

    @Test
    public void testSimplifyRdpToLimitReducesToLimit() throws Exception {
        ReplaySession session = buildSession("rdp-reduce", USER_ID, DEVICE_ID,
                0L, 50_000_000L, 50L);

        List<Position> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fifty.add(makePosition(i * 0.01, i * 0.01, new Date(i * 1_000_000L)));
        }
        storage.objectsToReturn = new ArrayList<>(fifty);

        List<Position> result = service.getOverview(session, 2);

        assertTrue(result.size() <= 2, "Expected <= 2 but got " + result.size());
    }


    private ReplaySession buildSession(String id, long userId, long deviceId, long from, long to, long totalCount) {
        ReplaySession s = new ReplaySession();
        s.setId(id);
        s.setUserId(userId);
        s.setDeviceId(deviceId);
        s.setFrom(from);
        s.setTo(to);
        s.setTotalCount(totalCount);
        s.setCreatedAt(System.currentTimeMillis());
        return s;
    }

    private Position makePosition(double lat, double lon, Date fixTime) {
        Position p = new Position();
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setFixTime(fixTime);
        return p;
    }
}

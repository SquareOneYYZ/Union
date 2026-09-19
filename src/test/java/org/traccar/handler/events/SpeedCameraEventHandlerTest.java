package org.traccar.handler.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.localCache.RedisCache;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage A of docs/plans/speed-camera-fix-plan.md: the handler compares knots to knots,
 * fires only past a configurable buffer over the limit (event.speedCamera.buffer, 5 km/h
 * default), treats a missing or non-positive limit as a non-reading, writes the payload in
 * Traccar units (knots) with the km/h duplicate kept for one release, and persists its state
 * with a TTL. Test ids (T-n) refer to plan section 4.2.
 *
 * The camera signal in these tests is {@code enforcement=maxspeed}, which is both the code
 * default and the prod value; the {@code highway} list is undeclared config until stage B
 * (finding D8) and its code default differs from prod, so it is not relied on here.
 */
public class SpeedCameraEventHandlerTest extends BaseTest {

    private static final double LIMIT_50_KMH_IN_KNOTS = 26.998; // what OverpassSpeedLimitProvider stores for "50"

    /**
     * In-memory stand-in for RedisCache. Records whether each write carried a TTL so the
     * test can pin the stage A requirement that state never lands without one.
     */
    private static final class FakeRedisCache extends RedisCache {
        private final Map<String, String> store = new HashMap<>();
        private final Map<String, Integer> ttls = new HashMap<>();
        private int plainSets;
        private boolean available = true;

        FakeRedisCache() {
            super((JedisPooled) null);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean exists(String key) {
            return store.containsKey(key);
        }

        @Override
        public String get(String key) {
            return store.get(key);
        }

        @Override
        public void set(String key, String value) {
            plainSets++;
            store.put(key, value);
        }

        @Override
        public void setWithTTL(String key, String value, int seconds) {
            store.put(key, value);
            ttls.put(key, seconds);
        }
    }

    private FakeRedisCache redis;
    private SpeedCameraEventHandler handler;
    private List<Event> events;

    @BeforeEach
    public void setUp() {
        redis = new FakeRedisCache();
        handler = new SpeedCameraEventHandler(redis, new Config());
        events = new ArrayList<>();
    }

    private Position cameraPosition(long fixEpochSeconds, double speedKnots, Double limitKnots) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setValid(true);
        position.setTime(new Date(fixEpochSeconds * 1000));
        position.setSpeed(speedKnots);
        position.set(Position.KEY_HIGHWAY, "speed_camera");
        position.set(Position.KEY_ENFORCEMENT, "maxspeed");
        if (limitKnots != null) {
            position.set(Position.KEY_SPEED_LIMIT, limitKnots);
        }
        return position;
    }

    private void process(Position position) {
        handler.onPosition(position, events::add);
    }

    @Test
    public void limitAbsentIsNonReading() { // T-1
        process(cameraPosition(0, 30, null));
        assertTrue(events.isEmpty(), "no limit must never fire");
        assertEquals(1, handler.getReadNoLimit());
    }

    @Test
    public void limitZeroIsNonReading() { // T-1, the primitive-zero case behind finding D2
        process(cameraPosition(0, 30, 0.0));
        assertTrue(events.isEmpty(), "a zero limit is an absent limit, not a 0 km/h zone");
        assertEquals(1, handler.getReadNoLimit());
    }

    @Test
    public void underLimitInKnotsDoesNotFire() { // T-2: 37 km/h in a 50 zone
        process(cameraPosition(0, 20, LIMIT_50_KMH_IN_KNOTS));
        assertTrue(events.isEmpty(), "20 kn is under a 26.998 kn limit; the old km/h compare fired here");
        assertEquals(0, handler.getReadNoLimit());
    }

    @Test
    public void atLimitDoesNotFire() {
        process(cameraPosition(0, LIMIT_50_KMH_IN_KNOTS, LIMIT_50_KMH_IN_KNOTS));
        assertTrue(events.isEmpty());
    }

    @Test
    public void bufferOfFiveKphIsAppliedOverTheLimit() { // T-4: event.speedCamera.buffer default 5 km/h
        process(cameraPosition(0, UnitsConverter.knotsFromKph(54.9), LIMIT_50_KMH_IN_KNOTS));
        assertTrue(events.isEmpty(), "4.9 km/h over a 50 limit is inside the 5 km/h buffer");
        process(cameraPosition(120, UnitsConverter.knotsFromKph(55.0), LIMIT_50_KMH_IN_KNOTS));
        assertTrue(events.isEmpty(), "exactly 5.0 km/h over is at the buffer, not past it");
        process(cameraPosition(240, UnitsConverter.knotsFromKph(55.1), LIMIT_50_KMH_IN_KNOTS));
        assertEquals(1, events.size(), "5.1 km/h over clears the buffer");
        assertEquals(UnitsConverter.knotsFromKph(55.1), events.get(0).getDouble("speed"), 0.0001);
    }

    @Test
    public void bufferIsConfigurable() {
        Config config = new Config();
        config.setString(Keys.EVENT_SPEED_CAMERA_BUFFER, "10");
        SpeedCameraEventHandler tenKph = new SpeedCameraEventHandler(new FakeRedisCache(), config);
        List<Event> got = new ArrayList<>();
        tenKph.onPosition(cameraPosition(0, UnitsConverter.knotsFromKph(59.9), LIMIT_50_KMH_IN_KNOTS), got::add);
        assertTrue(got.isEmpty(), "9.9 km/h over is inside a 10 km/h buffer");
        tenKph.onPosition(cameraPosition(120, UnitsConverter.knotsFromKph(60.1), LIMIT_50_KMH_IN_KNOTS), got::add);
        assertEquals(1, got.size());
    }

    @Test
    public void atLimitThroughADifferentConversionDoesNotFireEvenWithZeroBuffer() { // T-3b, equality guard
        Config config = new Config();
        config.setString(Keys.EVENT_SPEED_CAMERA_BUFFER, "0");
        SpeedCameraEventHandler noBuffer = new SpeedCameraEventHandler(new FakeRedisCache(), config);
        List<Event> got = new ArrayList<>();
        // 50 km/h from a decoder that rounds differently than knotsFromKph: 26.9979 vs 26.9978 kn.
        // 539 exported events sat here; a strict compare fires on the fifth decimal.
        noBuffer.onPosition(cameraPosition(0, 26.9979, 26.9978), got::add);
        assertTrue(got.isEmpty(), "the same km/h value must not fire because of conversion noise");
        // The smallest genuine over-limit reading in the export was 0.26 kn over; with no buffer that fires.
        noBuffer.onPosition(cameraPosition(120, 26.9978 + 0.26, 26.9978), got::add);
        assertEquals(1, got.size());
    }

    @Test
    public void overLimitFiresWithPayloadInKnots() { // T-3: 55.6 km/h in a 50 zone clears the 5 km/h buffer
        process(cameraPosition(0, 30, LIMIT_50_KMH_IN_KNOTS));
        assertEquals(1, events.size());
        Event event = events.get(0);
        assertEquals(Event.TYPE_SPEED_CAMERA, event.getType());
        assertEquals(1, event.getDeviceId());
        assertEquals(30, event.getDouble("speed"), 0.0001); // same key OverspeedProcessor uses
        assertEquals(LIMIT_50_KMH_IN_KNOTS, event.getDouble(Position.KEY_SPEED_LIMIT), 0.0001);
        assertEquals(30 * 1.852, event.getDouble("deviceSpeed"), 0.001); // deprecated km/h duplicate, one release
        assertEquals("speed_camera", event.getString(Position.KEY_HIGHWAY));
    }

    @Test
    public void noCameraTagDoesNotFire() {
        Position position = cameraPosition(0, 30, LIMIT_50_KMH_IN_KNOTS);
        position.set(Position.KEY_HIGHWAY, "residential");
        position.getAttributes().remove(Position.KEY_ENFORCEMENT);
        process(position);
        assertTrue(events.isEmpty());
    }

    @Test
    public void invalidPositionIsIgnored() {
        Position position = cameraPosition(0, 30, LIMIT_50_KMH_IN_KNOTS);
        position.setValid(false);
        process(position);
        assertTrue(events.isEmpty());
        assertNull(redis.store.get("speed_camera:1"), "an invalid position must not touch state");
    }

    @Test
    public void stateIsPersistedWithTtl() { // stage A half of D7
        process(cameraPosition(0, 30, LIMIT_50_KMH_IN_KNOTS));
        assertEquals(3600, redis.ttls.get("speed_camera:1"));
        assertEquals(0, redis.plainSets, "state must never be written without a TTL");
    }

    @Test
    public void repeatWithinLockIsSuppressedAndReleasedAfter() { // existing 60 s lock, kept until stage B
        process(cameraPosition(0, 30, LIMIT_50_KMH_IN_KNOTS));
        process(cameraPosition(30, 31, LIMIT_50_KMH_IN_KNOTS));
        assertEquals(1, events.size(), "second pass 30 s later on the same highway tag is a repeat");
        process(cameraPosition(61, 31, LIMIT_50_KMH_IN_KNOTS));
        assertEquals(2, events.size(), "the lock releases after 60 s");
    }

    @Test
    public void fallsBackToLocalCacheWhenRedisUnavailable() {
        redis.available = false;
        process(cameraPosition(0, 30, LIMIT_50_KMH_IN_KNOTS));
        process(cameraPosition(30, 31, LIMIT_50_KMH_IN_KNOTS));
        assertEquals(1, events.size(), "the lock must work from the local cache too");
        assertFalse(redis.store.containsKey("speed_camera:1"));
    }
}

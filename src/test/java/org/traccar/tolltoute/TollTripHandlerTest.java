package org.traccar.tolltoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.handler.events.BaseEventHandler;
import org.traccar.handler.events.TollEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.TollRouteState;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.RedisCache;
import java.util.Date;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;
public class TollTripHandlerTest {
    private CacheManager cacheManager;
    private Storage storage;
    private RedisCache redisCache;
    private TollEventHandler handler;
    private BaseEventHandler.Callback callback;

    @BeforeEach
    public void setup() {
        cacheManager = mock(CacheManager.class);
        storage = mock(Storage.class);
        redisCache = mock(RedisCache.class);
        Config config = mock(Config.class);
        callback = mock(BaseEventHandler.Callback.class);

        when(config.getInteger(Keys.EVENT_TOLL_ROUTE_MINIMAL_DURATION)).thenReturn(3);

        handler = new TollEventHandler(config, cacheManager, storage, redisCache);
    }

    private Position createPosition(long deviceId, boolean isToll, String tollName, double distance, Date time) {
        Position position = new Position();
        position.setDeviceId(deviceId);
        position.set(Position.KEY_TOLL, isToll);
        position.set(Position.KEY_TOLL_NAME, tollName);
        position.set(Position.KEY_TOLL_REF, "TOLL-123");
        position.set(Position.KEY_TOTAL_DISTANCE, distance);
        position.setFixTime(time);
        position.setValid(true);
        return position;
    }

    //  Enter toll → no event yet
    @Test
    public void testEnterTollNoEvent() {
        Device device = new Device();
        device.setId(1L);
        when(cacheManager.getObject(Device.class, 1L)).thenReturn(device);

        Position enterPosition = createPosition(1L, true, "Test Toll", 1000, new Date());

        handler.onPosition(enterPosition, callback);

        verify(callback, never()).eventDetected(any());
    }

    //  No exit yet → no event
    @Test
    public void testNoExitNoEvent() {
        Device device = new Device();
        device.setId(1L);
        when(cacheManager.getObject(Device.class, 1L)).thenReturn(device);

        Position enter1 = createPosition(1L, true, "Test Toll", 1000, new Date());
        Position enter2 = createPosition(1L, true, "Test Toll", 1100, new Date());

        handler.onPosition(enter1, callback);
        handler.onPosition(enter2, callback);

        verify(callback, never()).eventDetected(any());
    }
  //event trigger when exit
    @Test
    public void testTripCompletedEvent() {
        Device device = new Device();
        device.setId(1L);
        when(cacheManager.getObject(Device.class, 1L)).thenReturn(device);

        Date start = new Date(System.currentTimeMillis() - 60000);

        // 4 consecutive on-toll (true) → trip start confirmed
        Position enter1 = createPosition(1L, true, "Test Toll", 1000, start);
        Position enter2 = createPosition(1L, true, "Test Toll", 2000, new Date());
        Position enter3 = createPosition(1L, true, "Test Toll", 3000, new Date());
        Position enter4 = createPosition(1L, true, "Test Toll", 4000, new Date());

        // 4 consecutive off-toll (false) → trip end confirmed
        Position exit1 = createPosition(1L, false, "Test Toll", 5000, new Date());
        Position exit2 = createPosition(1L, false, "Test Toll", 6000, new Date());
        Position exit3 = createPosition(1L, false, "Test Toll", 7000, new Date());
        Position exit4 = createPosition(1L, false, "Test Toll", 8000, new Date());

        handler.onPosition(enter1, callback);
        handler.onPosition(enter2, callback);
        handler.onPosition(enter3, callback);
        handler.onPosition(enter4, callback);  // trip start confirmed
        handler.onPosition(exit1, callback);
        handler.onPosition(exit2, callback);
        handler.onPosition(exit3, callback);
        handler.onPosition(exit4, callback);   // trip end confirmed

        verify(callback, times(1)).eventDetected(argThat(event ->
                Event.TYPE_DEVICE_TOLLROUTE.equals(event.getType())
        ));
    }




}

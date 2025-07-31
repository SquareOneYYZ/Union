package org.traccar.handler.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.state.SurfaceState;
import org.traccar.storage.localCache.RedisCache;

import static org.mockito.Mockito.*;
public class SurfaceEventHandlerTest {

    @Test
    public void testSurfaceEventEmittedAfterConfidenceWindow() throws Exception {

        RedisCache redisCache = mock(RedisCache.class);

        SurfaceEventHandler handler = new SurfaceEventHandler(redisCache);

        long deviceId = 456;
        String cacheKey = "surface:" + deviceId;

        Position mockPosition = new Position();
        mockPosition.setDeviceId(deviceId);
        mockPosition.set(Position.KEY_SURFACE, "gravel");

        SurfaceState surfaceState = new SurfaceState();
        for (int i = 0; i < 3; i++) {
            surfaceState.addSurface("gravel", 4, mockPosition);
        }

        when(redisCache.exists(cacheKey)).thenReturn(true);
        when(redisCache.get(cacheKey)).thenReturn(new ObjectMapper().writeValueAsString(surfaceState));

        SurfaceEventHandler.Callback callback = mock(SurfaceEventHandler.Callback.class);

        handler.onPosition(mockPosition, callback);

        verify(redisCache, times(1)).set(eq(cacheKey), anyString());

        verify(callback, times(1)).eventDetected(any(Event.class));
    }



    @Test
    public void testUnknownSurfaceTypeIgnored() {
        RedisCache redisCache = mock(RedisCache.class);
        SurfaceEventHandler handler = new SurfaceEventHandler(redisCache);

        Position position = new Position();
        position.setDeviceId(999);
        position.set(Position.KEY_SURFACE, "plastic");

        SurfaceEventHandler.Callback callback = mock(SurfaceEventHandler.Callback.class);

        handler.onPosition(position, callback);

        verify(callback, never()).eventDetected(any());
        verify(redisCache, never()).set(any(), any());
    }



    @Test
    public void testNoSurfaceEventWithMixedSurfaces() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        SurfaceEventHandler handler = new SurfaceEventHandler(redisCache);

        long deviceId = 789;
        String cacheKey = "surface:" + deviceId;

        Position position = new Position();
        position.setDeviceId(deviceId);
        position.set(Position.KEY_SURFACE, "sand");

        SurfaceState state = new SurfaceState();
        state.addSurface("gravel", 4, position);
        state.addSurface("mud", 4, position);
        state.addSurface("sand", 4, position);

        when(redisCache.exists(cacheKey)).thenReturn(true);
        when(redisCache.get(cacheKey)).thenReturn(new ObjectMapper().writeValueAsString(state));

        SurfaceEventHandler.Callback callback = mock(SurfaceEventHandler.Callback.class);

        handler.onPosition(position, callback);

        verify(callback, never()).eventDetected(any());
    }


    @Test
    public void testNoSurfaceEventWithLessThanWindow() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        SurfaceEventHandler handler = new SurfaceEventHandler(redisCache);

        long deviceId = 123;
        String cacheKey = "surface:" + deviceId;

        Position position = new Position();
        position.setDeviceId(deviceId);
        position.set(Position.KEY_SURFACE, "gravel");

        SurfaceState state = new SurfaceState();
        for (int i = 0; i < 2; i++) {
            state.addSurface("gravel", 4, position);
        }

        when(redisCache.exists(cacheKey)).thenReturn(true);
        when(redisCache.get(cacheKey)).thenReturn(new ObjectMapper().writeValueAsString(state));

        SurfaceEventHandler.Callback callback = mock(SurfaceEventHandler.Callback.class);

        handler.onPosition(position, callback);

        verify(callback, never()).eventDetected(any());
    }




    @Test
    public void testSurfaceWithMixedCaseStillTriggers() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        SurfaceEventHandler handler = new SurfaceEventHandler(redisCache);

        long deviceId = 202;
        String cacheKey = "surface:" + deviceId;

        Position position = new Position();
        position.setDeviceId(deviceId);
        position.set(Position.KEY_SURFACE, "GrAvEl");

        SurfaceState state = new SurfaceState();
        for (int i = 0; i < 3; i++) {
            state.addSurface("gravel", 4, position);
        }

        when(redisCache.exists(cacheKey)).thenReturn(true);
        when(redisCache.get(cacheKey)).thenReturn(new ObjectMapper().writeValueAsString(state));

        SurfaceEventHandler.Callback callback = mock(SurfaceEventHandler.Callback.class);

        handler.onPosition(position, callback);

        verify(callback, times(1)).eventDetected(any());
    }





    @Test
    public void testInvalidJsonFromRedisHandled() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        SurfaceEventHandler handler = new SurfaceEventHandler(redisCache);

        long deviceId = 808;
        String cacheKey = "surface:" + deviceId;

        Position position = new Position();
        position.setDeviceId(deviceId);
        position.set(Position.KEY_SURFACE, "gravel");

        when(redisCache.exists(cacheKey)).thenReturn(true);
        when(redisCache.get(cacheKey)).thenReturn("INVALID_JSON");

        SurfaceEventHandler.Callback callback = mock(SurfaceEventHandler.Callback.class);

        handler.onPosition(position, callback);

        verify(callback, never()).eventDetected(any());
    }







}

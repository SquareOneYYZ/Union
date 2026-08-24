package org.traccar.api.resource;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.replay.ReplaySessionService;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.ReplaySession;
import org.traccar.storage.StorageException;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReplayResourceTest {

    private static final long USER_ID = 99L;
    private static final long DEVICE_ID = 42L;

    private static class StubReplaySessionService extends ReplaySessionService {

        private boolean cacheAvailable = true;
        private ReplaySession sessionToReturn = null;
        private List<Position> chunkToReturn = Collections.emptyList();
        private List<Position> overviewToReturn = Collections.emptyList();
        private StorageException chunkException = null;
        private StorageException overviewException = null;

        long lastCreateUserId;
        long lastCreateDeviceId;
        Date lastCreateFrom;
        Date lastCreateTo;
        ReplaySession lastChunkSession;
        int lastChunkOffset;
        int lastChunkLimit;
        ReplaySession lastOverviewSession;
        int lastOverviewLimit;

        StubReplaySessionService() {
            super(null, null, null);
        }

        @Override
        public boolean isCacheAvailable() {
            return cacheAvailable;
        }

        @Override
        public ReplaySession createSession(long userId, long deviceId, Date from, Date to)
                throws StorageException {
            lastCreateUserId = userId;
            lastCreateDeviceId = deviceId;
            lastCreateFrom = from;
            lastCreateTo = to;
            if (sessionToReturn != null) {
                return sessionToReturn;
            }
            ReplaySession s = new ReplaySession();
            s.setId("auto-id");
            s.setUserId(userId);
            s.setDeviceId(deviceId);
            s.setFrom(from.getTime());
            s.setTo(to.getTime());
            return s;
        }

        @Override
        public ReplaySession getSession(String sessionId) {
            return sessionToReturn;
        }

        @Override
        public List<Position> getChunk(ReplaySession session, int offset, int limit)
                throws StorageException {
            lastChunkSession = session;
            lastChunkOffset = offset;
            lastChunkLimit = limit;
            if (chunkException != null) {
                throw chunkException;
            }
            return chunkToReturn;
        }

        @Override
        public List<Position> getOverview(ReplaySession session, int limit)
                throws StorageException {
            lastOverviewSession = session;
            lastOverviewLimit = limit;
            if (overviewException != null) {
                throw overviewException;
            }
            return overviewToReturn;
        }
    }

    private static class StubPermissionsService extends PermissionsService {

        private boolean devicePermissionDenied = false;
        private boolean restrictionDenied = false;

        StubPermissionsService() {
            super(null);
        }

        @Override
        public <T extends org.traccar.model.BaseModel> void checkPermission(
                Class<T> clazz, long userId, long objectId) throws StorageException, SecurityException {
            if (devicePermissionDenied && clazz.equals(Device.class)) {
                throw new SecurityException("Device access denied");
            }
        }

        @Override
        public void checkRestriction(long userId,
                CheckRestrictionCallback callback) throws StorageException, SecurityException {
            if (restrictionDenied) {
                throw new SecurityException("Operation restricted");
            }
        }
    }


    private ReplayResource resource;
    private StubReplaySessionService replaySessionService;
    private StubPermissionsService permissionsService;

    @BeforeEach
    public void setUp() throws Exception {
        resource = new ReplayResource();

        replaySessionService = new StubReplaySessionService();
        permissionsService = new StubPermissionsService();

        SecurityContext securityContext = mockSecurityContext(USER_ID);

        injectField(resource, "securityContext", securityContext);
        injectField(resource, "permissionsService", permissionsService);
        injectField(resource, "replaySessionService", replaySessionService);
    }

    @Test
    public void testCreateSessionSuccess() throws Exception {
        Date from = new Date(1_000_000L);
        Date to   = new Date(2_000_000L);

        ReplaySession expected = buildSession("sess-1", USER_ID, DEVICE_ID, from.getTime(), to.getTime(), 5L);
        replaySessionService.sessionToReturn = expected;

        ReplaySession result = resource.createSession(buildRequest(DEVICE_ID, from, to));

        assertNotNull(result);
        assertEquals("sess-1", result.getId());
        assertEquals(USER_ID, result.getUserId());
        assertEquals(5L, result.getTotalCount());
    }

    @Test
    public void testCreateSessionNullRequestThrowsBadRequest() {
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.createSession(null));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testCreateSessionInvalidDeviceIdThrowsBadRequest() {
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.createSession(buildRequest(0L, new Date(1_000L), new Date(2_000L))));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testCreateSessionNullFromThrowsBadRequest() {
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.createSession(buildRequest(DEVICE_ID, null, new Date(2_000L))));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testCreateSessionNullToThrowsBadRequest() {
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.createSession(buildRequest(DEVICE_ID, new Date(1_000L), null)));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testCreateSessionFromAfterToThrowsBadRequest() {
        Date from = new Date(5_000_000L);
        Date to   = new Date(1_000_000L);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.createSession(buildRequest(DEVICE_ID, from, to)));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testCreateSessionWhenCacheUnavailableThrowsServiceUnavailable() throws Exception {
        replaySessionService.cacheAvailable = false;

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.createSession(buildRequest(DEVICE_ID, new Date(1_000L), new Date(2_000L))));
        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testCreateSessionPermissionDeniedThrowsSecurityException() throws Exception {
        permissionsService.devicePermissionDenied = true;

        assertThrows(SecurityException.class,
                () -> resource.createSession(buildRequest(DEVICE_ID, new Date(1_000L), new Date(2_000L))));
    }

    @Test
    public void testCreateSessionReportRestrictionThrows() throws Exception {
        permissionsService.restrictionDenied = true;

        assertThrows(SecurityException.class,
                () -> resource.createSession(buildRequest(DEVICE_ID, new Date(1_000L), new Date(2_000L))));
    }

    @Test
    public void testGetChunkSuccess() throws Exception {
        ReplaySession session = buildSession("chunk-1", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 3L);
        replaySessionService.sessionToReturn = session;
        replaySessionService.chunkToReturn = Arrays.asList(makePosition(1.0, 2.0), makePosition(1.1, 2.1));

        List<Position> result = resource.getChunk("chunk-1", 0, 10);

        assertEquals(2, result.size());
        assertEquals(10, replaySessionService.lastChunkLimit);
        assertEquals(0, replaySessionService.lastChunkOffset);
    }

    @Test
    public void testGetChunkDefaultLimitWhenZero() throws Exception {
        ReplaySession session = buildSession("chunk-default", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;

        resource.getChunk("chunk-default", 0, 0);

        assertEquals(100, replaySessionService.lastChunkLimit);
    }

    @Test
    public void testGetChunkCapsLimitAtMax() throws Exception {
        ReplaySession session = buildSession("chunk-cap", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;

        resource.getChunk("chunk-cap", 0, 9999);

        assertEquals(1000, replaySessionService.lastChunkLimit);
    }

    @Test
    public void testGetChunkNegativeOffsetBecomesZero() throws Exception {
        ReplaySession session = buildSession("chunk-neg", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;

        resource.getChunk("chunk-neg", -5, 0);

        assertEquals(0, replaySessionService.lastChunkOffset);
    }

    @Test
    public void testGetChunkSessionNotFoundThrowsNotFound() throws Exception {
        replaySessionService.sessionToReturn = null;

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.getChunk("missing", 0, 10));
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testGetChunkWrongUserThrowsForbidden() throws Exception {
        ReplaySession session = buildSession("chunk-forbidden", USER_ID + 1, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.getChunk("chunk-forbidden", 0, 10));
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testGetChunkPermissionDeniedForDevice() throws Exception {
        ReplaySession session = buildSession("chunk-perm", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;
        permissionsService.devicePermissionDenied = true;

        assertThrows(SecurityException.class,
                () -> resource.getChunk("chunk-perm", 0, 10));
    }

    @Test
    public void testGetOverviewSuccess() throws Exception {
        ReplaySession session = buildSession("ov-1", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 3L);
        replaySessionService.sessionToReturn = session;
        replaySessionService.overviewToReturn = Arrays.asList(
                makePosition(0.0, 0.0), makePosition(0.5, 0.5), makePosition(1.0, 1.0));

        List<Position> result = resource.getOverview("ov-1", 1000);

        assertEquals(3, result.size());
        assertEquals(1000, replaySessionService.lastOverviewLimit);
    }

    @Test
    public void testGetOverviewDefaultLimitWhenZero() throws Exception {
        ReplaySession session = buildSession("ov-default", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;

        resource.getOverview("ov-default", 0);

        assertEquals(1000, replaySessionService.lastOverviewLimit);
    }

    @Test
    public void testGetOverviewCapsLimitAtMax() throws Exception {
        ReplaySession session = buildSession("ov-cap", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;

        resource.getOverview("ov-cap", 99999);

        assertEquals(2000, replaySessionService.lastOverviewLimit);
    }

    @Test
    public void testGetOverviewSessionNotFoundThrowsNotFound() throws Exception {
        replaySessionService.sessionToReturn = null;

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.getOverview("not-there", 100));
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testGetOverviewWrongUserThrowsForbidden() throws Exception {
        ReplaySession session = buildSession("ov-forbidden", USER_ID + 1, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.getOverview("ov-forbidden", 100));
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    public void testGetOverviewPermissionDeniedForDevice() throws Exception {
        ReplaySession session = buildSession("ov-perm", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;
        permissionsService.devicePermissionDenied = true;

        assertThrows(SecurityException.class,
                () -> resource.getOverview("ov-perm", 100));
    }

    @Test
    public void testGetOverviewStorageExceptionPropagates() throws Exception {
        ReplaySession session = buildSession("ov-storage-err", USER_ID, DEVICE_ID, 1_000_000L, 5_000_000L, 1L);
        replaySessionService.sessionToReturn = session;
        replaySessionService.overviewException = new StorageException("db error");

        assertThrows(StorageException.class,
                () -> resource.getOverview("ov-storage-err", 100));
    }

    @Test
    public void testReplaySessionRequestGettersSetters() {
        ReplayResource.ReplaySessionRequest req = new ReplayResource.ReplaySessionRequest();
        Date from = new Date(1_000L);
        Date to   = new Date(2_000L);

        req.setDeviceId(7L);
        req.setFrom(from);
        req.setTo(to);

        assertEquals(7L, req.getDeviceId());
        assertEquals(from, req.getFrom());
        assertEquals(to, req.getTo());
    }

    private static SecurityContext mockSecurityContext(long userId) throws Exception {
        UserPrincipal principal = new UserPrincipal(userId, null);
        return new SecurityContext() {
            @Override
            public java.security.Principal getUserPrincipal() {
                return principal;
            }
            @Override
            public boolean isUserInRole(String role) {
                return false;
            }
            @Override
            public boolean isSecure() {
                return false;
            }
            @Override
            public String getAuthenticationScheme() {
                return null;
            }
        };
    }

    private ReplayResource.ReplaySessionRequest buildRequest(long deviceId, Date from, Date to) {
        ReplayResource.ReplaySessionRequest req = new ReplayResource.ReplaySessionRequest();
        req.setDeviceId(deviceId);
        req.setFrom(from);
        req.setTo(to);
        return req;
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

    private Position makePosition(double lat, double lon) {
        Position p = new Position();
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setFixTime(new Date());
        return p;
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found in class hierarchy of "
                + target.getClass().getName());
    }
}

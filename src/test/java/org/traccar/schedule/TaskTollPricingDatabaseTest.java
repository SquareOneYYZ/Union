package org.traccar.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.SecurityContext;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.resource.DirectoryResourceAccessor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.api.resource.TollTraversalResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.TollTraversal;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import javax.sql.DataSource;
import java.io.File;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The job against real tables: the project's full Liquibase changelog applied to an in-memory H2,
 * and the production {@link DatabaseStorage}. Proves the migration, the query shapes, the decimal
 * write, and that an unknown amount is stored as SQL NULL.
 *
 * <p>{@code TOLL_DB_URL} / {@code TOLL_DB_USER} / {@code TOLL_DB_PASSWORD} run the same test against
 * another database instead - an EMPTY scratch MySQL schema, never a real one: the changelog is applied
 * from scratch and the test writes rows. Verified on MySQL 8.0.40 on 2026-11-10.
 */
public class TaskTollPricingDatabaseTest {

    private static final String URL = System.getenv().getOrDefault(
            "TOLL_DB_URL", "jdbc:h2:mem:tollpricing;DB_CLOSE_DELAY=-1");
    private static final String USER = System.getenv().getOrDefault("TOLL_DB_USER", "sa");
    private static final String PASSWORD = System.getenv().getOrDefault("TOLL_DB_PASSWORD", "");

    private static DataSource dataSource;
    private static Storage storage;
    private static long deviceId;

    @BeforeAll
    public static void migrate() throws Exception {
        Database database = DatabaseFactory.getInstance().openDatabase(
                URL, USER, PASSWORD, null, null, null, null, new DirectoryResourceAccessor(new File(".")));
        try (Liquibase liquibase = new Liquibase("schema/changelog-master.xml",
                new DirectoryResourceAccessor(new File(".")), database)) {
            liquibase.update(new Contexts());
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(URL);
        hikari.setUsername(USER);
        hikari.setPassword(PASSWORD);
        hikari.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(hikari);
        storage = new DatabaseStorage(mock(Config.class), dataSource, new ObjectMapper());

        Device device = new Device();
        device.setName("toll-test");
        device.setUniqueId("toll-test-1");
        deviceId = storage.addObject(device, new Request(new Columns.Exclude("id")));
    }

    private static long position(double lat, double lon, String time) throws Exception {
        Position p = new Position("test");
        p.setDeviceId(deviceId);
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setValid(true);
        p.setTime(Date.from(Instant.parse(time)));
        return storage.addObject(p, new Request(new Columns.Exclude("id")));
    }

    private static long event(String type, long positionId, String time, Double tollDistance) throws Exception {
        Event e = new Event(type, deviceId);
        e.setPositionId(positionId);
        e.setEventTime(Date.from(Instant.parse(time)));
        if (tollDistance != null) {
            e.set("tollDistance", tollDistance);
        }
        return storage.addObject(e, new Request(new Columns.Exclude("id")));
    }

    private static TaskTollPricing task() {
        Config config = mock(Config.class);
        when(config.getBoolean(Keys.TOLL_PRICING_ENABLE)).thenReturn(true);
        when(config.getInteger(Keys.TOLL_PRICING_PERIOD)).thenReturn(300);
        TaskTollPricing task = new TaskTollPricing(config, storage);
        task.schedule(mock(ScheduledExecutorService.class));
        return task;
    }

    @Test
    public void pricesFromLaunchForwardAndStoresNullForUnknown() throws Exception {
        // History before launch must not be priced.
        event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, 0, "2026-03-16T10:00:00Z", 1000.0);
        TaskTollPricing task = task();
        task.run();
        assertTrue(storage.getObjects(TollTraversal.class,
                new Request(new Columns.All(), new Condition.Equals("deviceId", deviceId))).isEmpty());

        // A full 407 traversal (Hwy 403 -> Hwy 400), then an orphan exit.
        long in = position(43.526442, -79.735966, "2026-03-17T12:12:00Z");
        long out = position(43.783547, -79.535593, "2026-03-17T12:40:00Z");
        long enter = event(Event.TYPE_DEVICE_TOLLROUTE_ENTER, in, "2026-03-17T12:12:00Z", null);
        long exit = event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, out, "2026-03-17T12:40:00Z", 42300.0);
        long orphan = event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, out, "2026-03-17T13:00:00Z", 10.0);
        task.run();

        List<TollTraversal> rows = storage.getObjects(TollTraversal.class, new Request(new Columns.All(),
                new Condition.Equals("deviceId", deviceId), new Order("exitEventId")));
        assertEquals(2, rows.size());
        TollTraversal priced = rows.get(0);
        assertEquals(exit, priced.getExitEventId());
        assertEquals(enter, priced.getEnterEventId());
        assertEquals("407-etr", priced.getFacilityId());
        assertEquals(0, new BigDecimal("42.89").compareTo(priced.getAmount()));
        assertEquals("eastbound", priced.getDirection());

        TollTraversal unpriced = rows.get(1);
        assertEquals(orphan, unpriced.getExitEventId());
        assertEquals("NO_ENTER_EVENT", unpriced.getReason());
        assertNull(unpriced.getAmount());
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT amount, entereventid FROM tc_toll_traversal WHERE exiteventid = " + orphan)) {
            assertTrue(rs.next());
            assertNull(rs.getObject("amount"));          // SQL NULL, not 0
            assertNull(rs.getObject("entereventid"));    // 0 id stored as NULL
        }

        // Re-running finds nothing new: one row per exit, ever.
        task.run();
        assertEquals(2, storage.getObjects(TollTraversal.class,
                new Request(new Columns.All(), new Condition.Equals("deviceId", deviceId))).size());
    }

    /**
     * A gantry road the map splits in two (the Beachline pattern), on SR 408 eastbound at 100 km/h:
     * piece 1 ends before Hiawassee Main, piece 2 starts after it. The gap's plaza must be charged
     * once, on piece 2, whose trail starts at piece 1's exit.
     */
    @Test
    public void fragmentedGantryTripCountsTheGapOnce() throws Exception {
        Device device = new Device();
        device.setName("toll-fragment");
        device.setUniqueId("toll-fragment-1");
        long fragmentDevice = storage.addObject(device, new Request(new Columns.Exclude("id")));
        long saved = deviceId;
        deviceId = fragmentDevice;
        try {
            event(Event.TYPE_DEVICE_TOLLROUTE_ENTER, 0, "2026-09-18T09:00:00Z", null); // launch marker
            TaskTollPricing task = task();
            task.run();

            long t = Instant.parse("2026-09-18T10:00:00Z").toEpochMilli();
            long[] ids = new long[31];
            for (int i = 0; i <= 30; i++) {   // -81.505 .. -81.430, one fix every 10 s
                ids[i] = position(28.5482, -81.505 + i * 0.0025, Instant.ofEpochMilli(t + i * 10_000L).toString());
            }
            // piece 1: fixes 0..2 (to -81.500); piece 2: fixes 7..30 (from -81.4875); Hiawassee Main is at -81.4922
            event(Event.TYPE_DEVICE_TOLLROUTE_ENTER, ids[0], Instant.ofEpochMilli(t).toString(), null);
            long exit1 = event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, ids[2],
                    Instant.ofEpochMilli(t + 20_000L).toString(), 500.0);
            event(Event.TYPE_DEVICE_TOLLROUTE_ENTER, ids[7], Instant.ofEpochMilli(t + 70_000L).toString(), null);
            long exit2 = event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, ids[30],
                    Instant.ofEpochMilli(t + 300_000L).toString(), 5800.0);
            task.run();

            List<TollTraversal> rows = storage.getObjects(TollTraversal.class, new Request(new Columns.All(),
                    new Condition.Equals("deviceId", fragmentDevice), new Order("exitEventId")));
            assertEquals(2, rows.size());
            assertEquals(exit1, rows.get(0).getExitEventId());
            assertEquals("NO_PASSAGES", rows.get(0).getReason());
            assertNull(rows.get(0).getAmount());
            assertNull(rows.get(0).getFacilityId());   // SQL NULL, not the text 'null'
            assertNull(rows.get(0).getCurrency());
            assertEquals(exit2, rows.get(1).getExitEventId());
            assertEquals("cfx", rows.get(1).getFacilityId());
            assertEquals(0, new BigDecimal("4.80").compareTo(rows.get(1).getAmount()));
            assertTrue(rows.get(1).getInputs().contains("previousFragmentExit"));
        } finally {
            deviceId = saved;
        }
    }

    private static long traversal(long device, long exitEventId, String exitTime, BigDecimal amount, String reason)
            throws Exception {
        TollTraversal row = new TollTraversal();
        row.setDeviceId(device);
        row.setExitEventId(exitEventId);
        row.setExitTime(Date.from(Instant.parse(exitTime)));
        row.setAmount(amount);
        row.setCurrency(amount != null ? "USD" : null);
        row.setReason(reason);
        row.setCalcVersion(1);
        row.setPricedAt(new Date());
        return storage.addObject(row, new Request(amount == null
                ? new Columns.Exclude("id", "amount") : new Columns.Exclude("id")));
    }

    private static TollTraversalResource resource(PermissionsService permissions) throws Exception {
        TollTraversalResource resource = new TollTraversalResource() {
            {
                storage = TaskTollPricingDatabaseTest.storage;
                permissionsService = permissions;
            }
        };
        SecurityContext context = mock(SecurityContext.class);
        when(context.getUserPrincipal()).thenReturn(new UserPrincipal(42, null));
        Field field = BaseResource.class.getDeclaredField("securityContext");
        field.setAccessible(true);
        field.set(resource, context);
        return resource;
    }

    @Test
    public void resourceListsByExitTimeForPermittedDevicesOnly() throws Exception {
        Device a = new Device();
        a.setName("toll-api-a");
        a.setUniqueId("toll-api-a");
        long deviceA = storage.addObject(a, new Request(new Columns.Exclude("id")));
        Device b = new Device();
        b.setName("toll-api-b");
        b.setUniqueId("toll-api-b");
        long deviceB = storage.addObject(b, new Request(new Columns.Exclude("id")));
        traversal(deviceA, 900_001, "2026-10-01T10:00:00Z", new BigDecimal("4.80"), null);
        traversal(deviceA, 900_002, "2026-10-01T12:00:00Z", null, "NO_ENTER_EVENT");   // no enterTime
        traversal(deviceA, 900_003, "2026-10-05T12:00:00Z", new BigDecimal("1.00"), null); // outside range
        traversal(deviceB, 900_004, "2026-10-01T11:00:00Z", new BigDecimal("2.00"), null);
        Date from = Date.from(Instant.parse("2026-10-01T00:00:00Z"));
        Date to = Date.from(Instant.parse("2026-10-02T00:00:00Z"));

        PermissionsService permissions = mock(PermissionsService.class);
        List<TollTraversal> rows = new ArrayList<>(resource(permissions).get(List.of(deviceA), from, to));
        assertEquals(List.of(900_001L, 900_002L), rows.stream().map(TollTraversal::getExitEventId).toList());
        assertNull(rows.get(1).getAmount());
        verify(permissions).checkPermission(Device.class, 42, deviceA);

        rows = new ArrayList<>(resource(permissions).get(List.of(deviceA, deviceB, deviceA), from, to));
        assertEquals(3, rows.size());

        // One device the user cannot see fails the whole request, before anything is read.
        PermissionsService denying = mock(PermissionsService.class);
        doThrow(new SecurityException("Device access denied"))
                .when(denying).checkPermission(Device.class, 42, deviceB);
        assertThrows(SecurityException.class, () -> resource(denying).get(List.of(deviceA, deviceB), from, to));

        // Unbounded requests are refused.
        assertThrows(WebApplicationException.class, () -> resource(permissions).get(List.of(deviceA), null, to));
        assertThrows(WebApplicationException.class, () -> resource(permissions).get(List.of(), from, to));
        assertThrows(WebApplicationException.class, () -> resource(permissions).get(List.of(deviceA), to, from));
    }
}

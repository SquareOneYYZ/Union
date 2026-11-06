package org.traccar.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.TollTraversal;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The job against real tables: the project's full Liquibase changelog applied to an in-memory H2,
 * and the production {@link DatabaseStorage}. Proves the migration, the query shapes, the decimal
 * write, and that an unknown amount is stored as SQL NULL.
 */
public class TaskTollPricingDatabaseTest {

    private static final String URL = "jdbc:h2:mem:tollpricing;DB_CLOSE_DELAY=-1";

    private static JdbcDataSource dataSource;
    private static Storage storage;
    private static long deviceId;

    @BeforeAll
    public static void migrate() throws Exception {
        Database database = DatabaseFactory.getInstance().openDatabase(
                URL, "sa", "", "org.h2.Driver", null, null, null, new DirectoryResourceAccessor(new File(".")));
        try (Liquibase liquibase = new Liquibase("schema/changelog-master.xml",
                new DirectoryResourceAccessor(new File(".")), database)) {
            liquibase.update(new Contexts());
        }
        dataSource = new JdbcDataSource();
        dataSource.setURL(URL);
        dataSource.setUser("sa");
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
        assertTrue(storage.getObjects(TollTraversal.class, new Request(new Columns.All())).isEmpty());

        // A full 407 traversal (Hwy 403 -> Hwy 400), then an orphan exit.
        long in = position(43.526442, -79.735966, "2026-03-17T12:12:00Z");
        long out = position(43.783547, -79.535593, "2026-03-17T12:40:00Z");
        long enter = event(Event.TYPE_DEVICE_TOLLROUTE_ENTER, in, "2026-03-17T12:12:00Z", null);
        long exit = event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, out, "2026-03-17T12:40:00Z", 42300.0);
        long orphan = event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, out, "2026-03-17T13:00:00Z", 10.0);
        task.run();

        List<TollTraversal> rows = storage.getObjects(TollTraversal.class,
                new Request(new Columns.All(), new Order("exitEventId")));
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
        assertEquals(2, storage.getObjects(TollTraversal.class, new Request(new Columns.All())).size());
    }
}

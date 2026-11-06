package org.traccar.schedule;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.TollTraversal;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TaskTollPricingTest {

    private static Event event(long id, String type, long positionId, String time) {
        Event e = new Event();
        e.setId(id);
        e.setType(type);
        e.setDeviceId(7);
        e.setPositionId(positionId);
        e.setEventTime(Date.from(Instant.parse(time)));
        return e;
    }

    private static Position position(long id, double lat, double lon, String time) {
        Position p = new Position();
        p.setId(id);
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setFixTime(Date.from(Instant.parse(time)));
        return p;
    }

    private static TaskTollPricing task(Storage storage) {
        Config config = mock(Config.class);
        when(config.getBoolean(Keys.TOLL_PRICING_ENABLE)).thenReturn(true);
        when(config.getInteger(Keys.TOLL_PRICING_PERIOD)).thenReturn(300);
        TaskTollPricing task = new TaskTollPricing(config, storage);
        task.schedule(mock(ScheduledExecutorService.class)); // loads the registry, starts its own thread
        return task;
    }

    @Test
    public void pairsExitWithPrecedingEnterAndInsertsOneRow() throws Exception {
        Storage storage = mock(Storage.class);
        TollTraversal last = new TollTraversal();
        last.setExitEventId(100);
        when(storage.getObject(eq(TollTraversal.class), any())).thenReturn(last);
        Event exit = event(105, Event.TYPE_DEVICE_TOLLROUTE_EXIT, 12, "2026-03-17T12:40:00Z");
        exit.set("tollDistance", 42300.0);
        when(storage.getObjects(eq(Event.class), any())).thenReturn(
                List.of(exit),
                List.of(event(98, Event.TYPE_DEVICE_TOLLROUTE_EXIT, 1, "2026-03-17T11:00:00Z"),
                        event(101, Event.TYPE_DEVICE_TOLLROUTE_ENTER, 11, "2026-03-17T12:12:00Z"),
                        exit));
        when(storage.getObject(eq(Position.class), any())).thenReturn(
                position(11, 43.526442, -79.735966, "2026-03-17T12:12:00Z"),
                position(12, 43.783547, -79.535593, "2026-03-17T12:40:00Z"));

        task(storage).run();

        ArgumentCaptor<TollTraversal> row = ArgumentCaptor.forClass(TollTraversal.class);
        verify(storage).addObject(row.capture(), any());
        assertEquals(101, row.getValue().getEnterEventId());
        assertEquals(105, row.getValue().getExitEventId());
        assertEquals(0, new BigDecimal("42.89").compareTo(row.getValue().getAmount()));
        assertEquals(42300.0, row.getValue().getDistance());
    }

    @Test
    public void exitAfterAnotherExitHasNoEnterAndAmountIsLeftNull() throws Exception {
        Storage storage = mock(Storage.class);
        TollTraversal last = new TollTraversal();
        last.setExitEventId(100);
        when(storage.getObject(eq(TollTraversal.class), any())).thenReturn(last);
        Event exit = event(105, Event.TYPE_DEVICE_TOLLROUTE_EXIT, 12, "2026-03-17T12:40:00Z");
        when(storage.getObjects(eq(Event.class), any())).thenReturn(
                List.of(exit),
                List.of(event(101, Event.TYPE_DEVICE_TOLLROUTE_ENTER, 11, "2026-03-17T12:12:00Z"),
                        event(103, Event.TYPE_DEVICE_TOLLROUTE_EXIT, 1, "2026-03-17T12:20:00Z"),
                        exit));
        when(storage.getObject(eq(Position.class), any())).thenReturn(
                position(12, 43.783547, -79.535593, "2026-03-17T12:40:00Z"));

        task(storage).run();

        ArgumentCaptor<TollTraversal> row = ArgumentCaptor.forClass(TollTraversal.class);
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(storage).addObject(row.capture(), request.capture());
        assertNull(row.getValue().getAmount());
        assertEquals("NO_ENTER_EVENT", row.getValue().getReason());
        // amount is excluded from the insert so the column stays NULL, never 0 or "null"
        Columns columns = request.getValue().getColumns();
        assertTrue(columns instanceof Columns.Exclude);
        assertTrue(!columns.getColumns(TollTraversal.class, "set").contains("amount"));
    }

    @Test
    public void emptyTableStartsAtNewestEventAndPricesNothingYet() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObject(eq(TollTraversal.class), any())).thenReturn(null);
        Event newest = new Event();
        newest.setId(5000);
        when(storage.getObject(eq(Event.class), any())).thenReturn(newest);

        task(storage).run();

        verify(storage, never()).getObjects(eq(Event.class), any());
        verify(storage, never()).addObject(any(), any());
    }
}

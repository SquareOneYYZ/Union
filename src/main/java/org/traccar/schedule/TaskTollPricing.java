package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.TollTraversal;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.tollpricing.FacilityRegistry;
import org.traccar.tollpricing.GantrySet;
import org.traccar.tollpricing.PassageDetector;
import org.traccar.tollpricing.TraversalBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Prices toll traversals after the fact (docs/toll-pricing/23): every few minutes, takes the toll
 * exit events newer than the last one priced, pairs each with the device's preceding enter, and
 * writes one {@code tc_toll_traversal} row per exit.
 *
 * <p>Read-only over detection: it reads {@code tc_events} and {@code tc_positions} and writes only
 * {@code tc_toll_traversal}. It runs on its own thread so a slow database never delays the shared
 * scheduler's other tasks.
 *
 * <p>Query shapes, chosen for the production tables: new exits by primary-key range above the
 * watermark; the enter by {@code (deviceid, eventtime)} over a bounded window; positions by id.
 * The watermark is the highest {@code exiteventid} in the table. With an empty table it starts at
 * the current newest event id - pricing runs from launch forward, and the backlog since 2026-08-27
 * is a separate backfill.
 */
public class TaskTollPricing extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskTollPricing.class);

    /** Longest traversal considered when looking back from an exit for its enter. */
    static final long MAX_TRAVERSAL_MS = TimeUnit.HOURS.toMillis(12);

    static final int BATCH = 500;

    /**
     * A device whose previous toll exit was this recent before the enter is on one road the map
     * split in two (the SR 528 Beachline toll=yes/no interleave): the trail is read from that exit,
     * so gantries in the gap are counted, and the two trails never overlap.
     */
    static final long FRAGMENT_GAP_MS = TimeUnit.MINUTES.toMillis(15);

    /** Upper bound on positions read for one traversal's trail. */
    static final int MAX_TRAIL = 5000;

    private record Pairing(Event enter, Event previousExit) {
    }

    private final Storage storage;
    private final boolean enabled;
    private final int periodSeconds;
    private FacilityRegistry registry;
    private TraversalBuilder builder;
    private long watermark;

    @Inject
    public TaskTollPricing(Config config, Storage storage) {
        this.storage = storage;
        this.enabled = config.getBoolean(Keys.TOLL_PRICING_ENABLE);
        this.periodSeconds = config.getInteger(Keys.TOLL_PRICING_PERIOD);
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        if (!enabled) {
            return;
        }
        try {
            registry = FacilityRegistry.loadResource();
        } catch (IOException e) {
            LOGGER.error("Toll pricing disabled: registry failed to load", e);
            return;
        }
        builder = new TraversalBuilder(registry);
        for (FacilityRegistry.Facility f : registry.getFacilities()) {
            LOGGER.info("Toll pricing facility {} (card {}, osm_base {})",
                    f.id(), f.pricer().getCardValidFrom(), f.corridor().getOsmBase());
        }
        for (GantrySet s : registry.getGantrySets()) {
            LOGGER.info("Toll pricing facility {} (cards {}, osm_base {}, {} toll points, unlocated plazas {})",
                    s.getFacilityId(), s.getCards(), s.getOsmBase(), s.getPoints().size(), s.getUnlocated());
        }
        if (!registry.getSkipped().isEmpty()) {
            LOGGER.info("Toll pricing: registry entries not yet priceable: {}", registry.getSkipped());
        }
        ScheduledExecutorService own = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "toll-pricing");
            thread.setDaemon(true);
            return thread;
        });
        own.scheduleWithFixedDelay(this, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        try {
            if (!advanceWatermark()) {
                return;
            }
            List<Event> exits = storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Compare("id", ">", "id", watermark),
                            new Condition.Equals("type", Event.TYPE_DEVICE_TOLLROUTE_EXIT)),
                    new Order("id", false, BATCH)));
            int priced = 0;
            for (Event exit : exits) {
                TollTraversal row = priceOne(exit);
                insert(row);
                watermark = Math.max(watermark, exit.getId());
                if (row.getAmount() != null) {
                    priced++;
                }
            }
            if (!exits.isEmpty()) {
                LOGGER.info("Toll pricing: {} exits, {} priced, watermark {}", exits.size(), priced, watermark);
            }
        } catch (StorageException | RuntimeException e) {
            LOGGER.warn("Toll pricing run failed; retrying next period", e);
        }
    }

    /** @return false when the watermark was only just initialised (nothing to do this run) */
    private boolean advanceWatermark() throws StorageException {
        TollTraversal latest = storage.getObject(TollTraversal.class, new Request(
                new Columns.Include("exitEventId"), new Order("exitEventId", true, 1)));
        if (latest != null) {
            watermark = Math.max(watermark, latest.getExitEventId());
        }
        if (watermark == 0) {
            Event newest = storage.getObject(Event.class, new Request(
                    new Columns.Include("id"), new Order("id", true, 1)));
            watermark = newest != null ? newest.getId() : 0;
            LOGGER.info("Toll pricing starts after event id {} (launch forward)", watermark);
            return false;
        }
        return true;
    }

    private TollTraversal priceOne(Event exit) throws StorageException {
        Pairing pairing = findEnter(exit);
        Event enter = pairing.enter();
        TraversalBuilder.Edge enterEdge = enter != null ? edge(enter.getPositionId()) : null;
        TraversalBuilder.Edge exitEdge = edge(exit.getPositionId());
        Date trailStart = enterEdge != null ? enterEdge.fixTime() : null;
        Event previous = pairing.previousExit();
        if (enter != null && previous != null && previous.getEventTime() != null && enter.getEventTime() != null
                && enter.getEventTime().getTime() - previous.getEventTime().getTime() <= FRAGMENT_GAP_MS) {
            TraversalBuilder.Edge previousEdge = edge(previous.getPositionId());
            if (previousEdge != null && previousEdge.fixTime() != null) {
                trailStart = previousEdge.fixTime();
            }
        }
        Date from = trailStart;
        Date to = exitEdge != null ? exitEdge.fixTime() : null;
        return builder.build(exit.getDeviceId(), exit.getId(), enter != null ? enter.getId() : 0,
                enterEdge, exitEdge, exit.getDouble("tollDistance"), new Date(),
                () -> trail(exit.getDeviceId(), from, to), from);
    }

    /** The device's positions between two fix times, ordered, bounded; null when it cannot be read. */
    private List<PassageDetector.TrailPoint> trail(long deviceId, Date from, Date to) {
        if (from == null || to == null || !to.after(from)) {
            return null;
        }
        try {
            List<Position> positions = storage.getObjects(Position.class, new Request(
                    new Columns.Include("id", "latitude", "longitude", "fixTime"),
                    new Condition.And(
                            new Condition.Equals("deviceId", deviceId),
                            new Condition.Between("fixTime", "from", from, "to", to)),
                    new Order("fixTime", false, MAX_TRAIL)));
            List<PassageDetector.TrailPoint> trail = new ArrayList<>(positions.size());
            for (Position p : positions) {
                trail.add(new PassageDetector.TrailPoint(p.getLatitude(), p.getLongitude(), p.getFixTime()));
            }
            return trail;
        } catch (StorageException e) {
            LOGGER.warn("Toll pricing: trail read failed for device {}", deviceId, e);
            return null;
        }
    }

    /**
     * The device's latest toll event before this exit, if it is an enter. Another exit in between
     * means this exit has no enter of its own.
     */
    private Pairing findEnter(Event exit) throws StorageException {
        Date to = exit.getEventTime();
        if (to == null) {
            return new Pairing(null, null);
        }
        List<Event> events = storage.getObjects(Event.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", exit.getDeviceId()),
                        new Condition.Between("eventTime",
                                "from", new Date(to.getTime() - MAX_TRAVERSAL_MS), "to", to)),
                new Order("eventTime")));
        Event candidate = null;
        Event lastExit = null;
        Event exitBeforeCandidate = null;
        for (Event e : events) {
            if (e.getId() >= exit.getId()) {
                continue;
            }
            if (Event.TYPE_DEVICE_TOLLROUTE_ENTER.equals(e.getType())) {
                candidate = e;
                exitBeforeCandidate = lastExit;
            } else if (Event.TYPE_DEVICE_TOLLROUTE_EXIT.equals(e.getType())) {
                candidate = null;
                lastExit = e;
            }
        }
        return new Pairing(candidate, candidate != null ? exitBeforeCandidate : null);
    }

    private TraversalBuilder.Edge edge(long positionId) throws StorageException {
        if (positionId == 0) {
            return null;
        }
        Position p = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.Equals("id", positionId)));
        return p == null ? null
                : new TraversalBuilder.Edge(p.getId(), p.getLatitude(), p.getLongitude(), p.getFixTime());
    }

    /** NULL columns are left out of the insert so they default to NULL (the storage layer cannot write one). */
    private void insert(TollTraversal row) throws StorageException {
        Columns columns = row.getAmount() == null
                ? new Columns.Exclude("id", "amount")
                : new Columns.Exclude("id");
        row.setId(storage.addObject(row, new Request(columns)));
    }
}

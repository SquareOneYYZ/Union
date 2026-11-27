package org.traccar.tollpricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.TollTraversal;
import org.traccar.session.state.TollRouteProcessor;
import org.traccar.session.state.TollRouteState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * WP4 offline pricing dry-run (docs/toll-pricing/20 section 6): one day of real toll readings from
 * production, replayed through the production detection state machine ({@link TollRouteState} and
 * {@link TollRouteProcessor}, as {@code TollEventHandler} drives them) into enter/exit events, then
 * paired and priced exactly as {@code TaskTollPricing} does, through the production
 * {@link TraversalBuilder} and the packaged registry.
 *
 * <p>Runs only when {@code TOLL_DRYRUN_EXPORT} names the export (toll_event_trend.sql section 13
 * columns) and {@code TOLL_DRYRUN_OUT} a directory for the results. The export holds device
 * coordinates: the outputs must never be committed.
 *
 * <p>Faithful to production except where the export cannot be: every device starts the day with
 * fresh state (a device already on a toll road at midnight gets an extra enter); only positions that
 * were looked up are in the export, so trails are thinner than {@code tc_positions} (gantry and ramp
 * hits can only be missed, not invented); the minimal duration is the one passed in, not the one in
 * force that day.
 */
public class PricingDryRun {

    /** Mirrors TaskTollPricing.MAX_TRAVERSAL_MS / FRAGMENT_GAP_MS / MAX_TRAIL. */
    static final long MAX_TRAVERSAL_MS = TimeUnit.HOURS.toMillis(12);
    static final long FRAGMENT_GAP_MS = TimeUnit.MINUTES.toMillis(15);
    static final int MAX_TRAIL = 5000;

    private record Row(long deviceId, long id, Date fixTime, double lat, double lon, boolean valid,
                       Boolean isToll, double totalDistance, boolean stale, String tollRef) {
    }

    private record Simulated(long id, long deviceId, String type, long positionId, Date eventTime,
                             double tollDistance, String tollRef) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TOLL_DRYRUN_EXPORT", matches = ".+")
    public void dryRun() throws Exception {
        Path export = Path.of(System.getenv("TOLL_DRYRUN_EXPORT"));
        Path out = Path.of(System.getenv("TOLL_DRYRUN_OUT"));
        int duration = Integer.parseInt(System.getenv().getOrDefault("TOLL_DRYRUN_DURATION", "4"));
        Files.createDirectories(out);

        Map<Long, List<Row>> byDevice = load(export);
        Map<Long, Row> byId = new HashMap<>();
        byDevice.values().forEach(rows -> rows.forEach(r -> byId.put(r.id(), r)));

        // 1. Detection: the production state machine, fed as TollEventHandler feeds it.
        List<Simulated> events = new ArrayList<>();
        long eventId = 1;
        for (Map.Entry<Long, List<Row>> e : byDevice.entrySet()) {
            List<Row> rows = new ArrayList<>(e.getValue());
            rows.sort(Comparator.comparingLong(Row::id));   // arrival order
            Device device = new Device();
            device.setId(e.getKey());
            TollRouteState state = new TollRouteState();
            state.fromDevice(device);
            for (Row r : rows) {
                if (r.stale() || !r.valid()) {
                    continue;   // TollEventHandler: !isLatest || !valid
                }
                Position p = new Position();
                p.setId(r.id());
                p.setDeviceId(r.deviceId());
                p.setFixTime(r.fixTime());
                p.setDeviceTime(r.fixTime());
                p.setLatitude(r.lat());
                p.setLongitude(r.lon());
                p.setValid(true);
                p.set(Position.KEY_TOTAL_DISTANCE, r.totalDistance());
                state.addOnToll(r.isToll(), duration, p);
                TollRouteProcessor.updateState(state, p, r.tollRef(), null, duration);
                Event ev = state.getEvent();
                if (ev != null) {
                    events.add(new Simulated(eventId++, r.deviceId(), ev.getType(), ev.getPositionId(),
                            ev.getEventTime(), ev.getDouble(TollRouteProcessor.ATTRIBUTE_TOLL_DIST),
                            ev.getString(Position.KEY_TOLL_REF)));
                }
            }
        }

        // 2. Pricing: TaskTollPricing.priceOne for every exit, in event order.
        TraversalBuilder builder = new TraversalBuilder(FacilityRegistry.loadResource());
        Map<Long, List<Simulated>> eventsByDevice = new HashMap<>();
        events.forEach(ev -> eventsByDevice.computeIfAbsent(ev.deviceId(), k -> new ArrayList<>()).add(ev));
        // TaskTollPricing.findEnter reads the device's events ordered by eventTime.
        eventsByDevice.values().forEach(list -> list.sort(
                Comparator.comparing(Simulated::eventTime).thenComparingLong(Simulated::id)));
        List<TollTraversal> rows = new ArrayList<>();
        List<Simulated> exits = new ArrayList<>();
        int fragments = 0;
        for (Simulated exit : events) {
            if (!Event.TYPE_DEVICE_TOLLROUTE_EXIT.equals(exit.type())) {
                continue;
            }
            Simulated enter = null;
            Simulated lastExit = null;
            Simulated exitBeforeEnter = null;
            for (Simulated ev : eventsByDevice.get(exit.deviceId())) {
                if (ev.id() >= exit.id() || ev.eventTime().getTime() < exit.eventTime().getTime() - MAX_TRAVERSAL_MS
                        || ev.eventTime().after(exit.eventTime())) {
                    continue;
                }
                if (Event.TYPE_DEVICE_TOLLROUTE_ENTER.equals(ev.type())) {
                    enter = ev;
                    exitBeforeEnter = lastExit;
                } else {
                    enter = null;
                    lastExit = ev;
                }
            }
            TraversalBuilder.Edge enterEdge = enter != null ? edge(byId.get(enter.positionId())) : null;
            TraversalBuilder.Edge exitEdge = edge(byId.get(exit.positionId()));
            Date trailStart = enterEdge != null ? enterEdge.fixTime() : null;
            if (enter != null && exitBeforeEnter != null
                    && enter.eventTime().getTime() - exitBeforeEnter.eventTime().getTime() <= FRAGMENT_GAP_MS) {
                TraversalBuilder.Edge previous = edge(byId.get(exitBeforeEnter.positionId()));
                if (previous != null) {
                    trailStart = previous.fixTime();
                    fragments++;
                }
            }
            Date from = trailStart;
            Date to = exitEdge != null ? exitEdge.fixTime() : null;
            List<Row> deviceRows = byDevice.get(exit.deviceId());
            TollTraversal row = builder.build(exit.deviceId(), exit.id(), enter != null ? enter.id() : 0,
                    enterEdge, exitEdge, exit.tollDistance(), new Date(),
                    () -> trail(deviceRows, from, to), from);
            rows.add(row);
            exits.add(exit);
        }

        write(out, rows, exits, events, fragments, duration, export, byDevice.size(), byId.size());
    }

    private static TraversalBuilder.Edge edge(Row r) {
        return r == null ? null : new TraversalBuilder.Edge(r.id(), r.lat(), r.lon(), r.fixTime());
    }

    /** As TaskTollPricing.trail: every stored position (stale included) with fixTime in [from, to]. */
    private static List<PassageDetector.TrailPoint> trail(List<Row> rows, Date from, Date to) {
        if (from == null || to == null || !to.after(from)) {
            return null;
        }
        List<PassageDetector.TrailPoint> trail = new ArrayList<>();
        rows.stream()
                .filter(r -> !r.fixTime().before(from) && !r.fixTime().after(to))
                .sorted(Comparator.comparing(Row::fixTime))
                .limit(MAX_TRAIL)
                .forEach(r -> trail.add(new PassageDetector.TrailPoint(r.lat(), r.lon(), r.fixTime())));
        return trail;
    }

    private static Map<Long, List<Row>> load(Path export) throws IOException {
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));   // exported from a UTC session
        Map<Long, List<Row>> byDevice = new TreeMap<>();
        // ISO-8859-1 keeps MySQL's raw BIT(1) byte (0x01) for `valid` intact.
        try (BufferedReader in = Files.newBufferedReader(export, StandardCharsets.ISO_8859_1)) {
            String[] header = in.readLine().split("\t");
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                col.put(header[i], i);
            }
            for (String name : List.of("deviceid", "id", "fixtime", "latitude", "longitude", "valid", "is_toll",
                    "failed", "toll_ref", "total_distance", "stale")) {
                if (!col.containsKey(name)) {
                    throw new IOException("export lacks column " + name);
                }
            }
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                String valid = f[col.get("valid")];
                String isToll = f[col.get("is_toll")];
                Boolean reading = "1".equals(f[col.get("failed")]) ? null
                        : "true".equals(isToll) ? Boolean.TRUE : "false".equals(isToll) ? Boolean.FALSE : null;
                String ref = f[col.get("toll_ref")];
                double distance;
                try {
                    distance = Double.parseDouble(f[col.get("total_distance")]);
                } catch (NumberFormatException e) {
                    distance = 0;
                }
                Row r;
                try {
                    r = new Row(Long.parseLong(f[col.get("deviceid")]), Long.parseLong(f[col.get("id")]),
                            utc.parse(f[col.get("fixtime")]), Double.parseDouble(f[col.get("latitude")]),
                            Double.parseDouble(f[col.get("longitude")]),
                            "\u0001".equals(valid) || "1".equals(valid), reading, distance,
                            "1".equals(f[col.get("stale")]), "NULL".equals(ref) || ref.isEmpty() ? null : ref);
                } catch (java.text.ParseException e) {
                    throw new IOException("bad fixtime in " + line, e);
                }
                byDevice.computeIfAbsent(r.deviceId(), k -> new ArrayList<>()).add(r);
            }
        }
        return byDevice;
    }

    private static void write(Path out, List<TollTraversal> rows, List<Simulated> exits, List<Simulated> events,
                              int fragments, int duration, Path export, int devices, int positions)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("traversals.tsv")))) {
            w.println("device\texit_event\tenter_event\tenter_time\texit_time\ttoll_ref\tfacility\treason\tamount"
                    + "\tcurrency\tdirection\tcard\ttoll_distance_m\tmixed_operators\tfragment_trail\tpassages\tinputs");
            for (int i = 0; i < rows.size(); i++) {
                TollTraversal r = rows.get(i);
                JsonNode inputs = mapper.readTree(r.getInputs());
                w.println(String.join("\t", String.valueOf(r.getDeviceId()), String.valueOf(r.getExitEventId()),
                        String.valueOf(r.getEnterEventId()),
                        r.getEnterTime() != null ? iso.format(r.getEnterTime()) : "",
                        r.getExitTime() != null ? iso.format(r.getExitTime()) : "",
                        String.valueOf(exits.get(i).tollRef()), String.valueOf(r.getFacilityId()),
                        String.valueOf(r.getReason()), r.getAmount() != null ? r.getAmount().toPlainString() : "",
                        String.valueOf(r.getCurrency()), String.valueOf(r.getDirection()),
                        String.valueOf(r.getCardValidFrom()), String.valueOf(Math.round(r.getDistance())),
                        String.valueOf(inputs.has("mixedOperators")),
                        String.valueOf(inputs.path("trailFrom").asText("")),
                        inputs.has("passages") ? inputs.get("passages").toString() : "",
                        r.getInputs()));
            }
        }

        Map<String, int[]> byFacilityReason = new TreeMap<>();
        Map<String, BigDecimal> totals = new TreeMap<>();
        Map<String, Integer> unpricedRefs = new TreeMap<>();
        int priced = 0;
        for (int i = 0; i < rows.size(); i++) {
            TollTraversal r = rows.get(i);
            String key = r.getFacilityId() + " | " + (r.getReason() != null ? r.getReason() : "PRICED");
            byFacilityReason.computeIfAbsent(key, k -> new int[1])[0]++;
            if (r.getAmount() != null) {
                priced++;
                totals.merge(r.getFacilityId() + " " + r.getCurrency(), r.getAmount(), BigDecimal::add);
            } else if (TraversalBuilder.REASON_NO_FACILITY.equals(r.getReason())) {
                unpricedRefs.merge(String.valueOf(exits.get(i).tollRef()), 1, Integer::sum);
            }
        }
        long enters = events.stream().filter(e -> Event.TYPE_DEVICE_TOLLROUTE_ENTER.equals(e.type())).count();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.txt")))) {
            w.println("WP4 offline pricing dry-run");
            w.println("export: " + export.getFileName() + " (" + devices + " devices, " + positions + " positions)");
            w.println("minimalDuration: " + duration + "; calc version " + TollEstimate.CALC_VERSION);
            w.println("simulated enters: " + enters + ", exits: " + rows.size() + ", priced: " + priced
                    + ", trails read from a previous fragment's exit: " + fragments);
            w.println();
            w.println("facility | reason -> traversals");
            byFacilityReason.forEach((k, v) -> w.println("  " + k + " -> " + v[0]));
            w.println();
            w.println("priced total by facility");
            totals.forEach((k, v) -> w.println("  " + k + " " + v.toPlainString()));
            w.println();
            w.println("NO_FACILITY exits by detection tollRef");
            unpricedRefs.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> w.println("  " + e.getKey() + " -> " + e.getValue()));
        }
    }
}

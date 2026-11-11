package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.traccar.model.TollTraversal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The A-30 over the packaged card: one toll station on the Saint Lawrence crossing (pont P-10942),
 * Categorie B 2.20 $ per axle = 4.40 $ for a 2-axle car, both directions, from 2026-02-28.
 *
 * <p>The trail is a real southbound crossing (device 1675, 2026-09-03 01:24-01:32 UTC, from the
 * production export {@code data prod/export_sep3.tsv}); detection marked 01:27:02-01:30:23 as tolled.
 */
public class A30PricingTest {

    private static final double[][] CROSSING = {
            {45.36547, -74.049683}, {45.358065, -74.047861}, {45.350388, -74.046915}, {45.343846, -74.042555},
            {45.337478, -74.037738}, {45.331108, -74.03292}, {45.325, -74.028386}, {45.319518, -74.024383},
            {45.313815, -74.020136}, {45.308413, -74.016135}, {45.303053, -74.012155}, {45.29737, -74.007876},
            {45.294393, -74.001121}, {45.295028, -73.993755}, {45.297001, -73.983536}};
    private static final String[] TIMES = {
            "01:24:03", "01:24:33", "01:25:03", "01:25:32", "01:26:02", "01:26:32", "01:27:02", "01:27:31",
            "01:28:31", "01:29:01", "01:29:30", "01:30:00", "01:30:23", "01:30:45", "01:31:15"};

    private static FacilityRegistry registry;

    @BeforeAll
    public static void load() throws Exception {
        registry = FacilityRegistry.loadResource();
    }

    private static List<PassageDetector.TrailPoint> crossing(String day, boolean reversed) {
        List<PassageDetector.TrailPoint> trail = new ArrayList<>();
        for (int i = 0; i < CROSSING.length; i++) {
            int j = reversed ? CROSSING.length - 1 - i : i;
            trail.add(new PassageDetector.TrailPoint(CROSSING[j][0], CROSSING[j][1],
                    Date.from(Instant.parse(day + "T" + TIMES[i] + "Z"))));
        }
        return trail;
    }

    private static List<GantrySet> a30() {
        return registry.getGantrySets().stream().filter(s -> s.getFacilityId().equals("a30")).toList();
    }

    @Test
    public void aCrossingIsOneTollEitherWay() {
        for (boolean reversed : new boolean[] {false, true}) {
            List<PassageDetector.TrailPoint> trail = crossing("2026-09-03", reversed);
            FlatPricer.Result r = FlatPricer.price(trail, a30(), trail.get(0).fixTime());
            assertEquals("a30", r.facilityId());
            assertEquals("CAD", r.estimate().getCurrency());
            assertEquals(0, new BigDecimal("4.40").compareTo(r.estimate().getAmount()), r.inputs().toString());
            assertEquals("2026-02-28", r.estimate().getCardValidFrom());
        }
    }

    @Test
    public void beforeThe2026GridIsUnknown() {
        List<PassageDetector.TrailPoint> trail = crossing("2026-02-27", false);
        FlatPricer.Result r = FlatPricer.price(trail, a30(), trail.get(0).fixTime());
        assertNull(r.estimate().getAmount());
        assertEquals(FlatPricer.REASON_NO_RATE, r.estimate().getReason());
    }

    @Test
    public void theDetectedTraversalIsPricedEndToEnd() {
        // The enter and exit events' positions: the first and last fixes detection marked tolled.
        List<PassageDetector.TrailPoint> trail = crossing("2026-09-03", false);
        TraversalBuilder.Edge enter = new TraversalBuilder.Edge(1, 45.325, -74.028386,
                Date.from(Instant.parse("2026-09-03T01:27:02Z")));
        TraversalBuilder.Edge exit = new TraversalBuilder.Edge(2, 45.294393, -74.001121,
                Date.from(Instant.parse("2026-09-03T01:30:23Z")));
        TollTraversal row = new TraversalBuilder(registry).build(1675, 20, 10, enter, exit, 3900, new Date(),
                () -> trail.subList(6, 13), null);
        assertEquals("a30", row.getFacilityId());
        assertEquals(0, new BigDecimal("4.40").compareTo(row.getAmount()), row.getInputs());
        assertNull(row.getReason());
    }
}

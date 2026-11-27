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
 * <p>The trail is the shape of a real southbound crossing at a real fix cadence (30-60 s, slowing at the
 * booth), moved to another day and detached from its device; detection marked the 7th to 13th fixes as
 * tolled.
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
            List<PassageDetector.TrailPoint> trail = crossing("2026-09-20", reversed);
            FlatPricer.Result r = FlatPricer.price(trail, a30(), trail.get(0).fixTime());
            assertEquals("a30", r.facilityId());
            assertEquals("CAD", r.estimate().getCurrency());
            assertEquals(0, new BigDecimal("4.40").compareTo(r.estimate().getAmount()), r.inputs().toString());
            assertEquals("2026-02-28", r.estimate().getCardValidFrom());
        }
    }

    @Test
    public void waitingAtTheBoothIsOnePassage() {
        // Queues at the booth for 6 minutes, fixes wandering a few metres, then drives on.
        double[][] points = {{45.3260, -74.0290}, {45.3200, -74.0247}, {45.31780, -74.02290}, {45.31784, -74.02286},
                {45.31779, -74.02292}, {45.31786, -74.02281}, {45.31777, -74.02295}, {45.31782, -74.02288},
                {45.3140, -74.0200}, {45.3080, -74.0160}};
        List<PassageDetector.TrailPoint> trail = new ArrayList<>();
        Instant t = Instant.parse("2026-09-20T01:20:00Z");
        for (double[] p : points) {
            trail.add(new PassageDetector.TrailPoint(p[0], p[1], Date.from(t)));
            t = t.plusSeconds(60);
        }
        FlatPricer.Result r = FlatPricer.price(trail, a30(), trail.get(0).fixTime());
        assertEquals(0, new BigDecimal("4.40").compareTo(r.estimate().getAmount()), r.inputs().toString());
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
        List<PassageDetector.TrailPoint> trail = crossing("2026-09-20", false);
        TraversalBuilder.Edge enter = new TraversalBuilder.Edge(1, 45.325, -74.028386,
                Date.from(Instant.parse("2026-09-20T01:27:02Z")));
        TraversalBuilder.Edge exit = new TraversalBuilder.Edge(2, 45.294393, -74.001121,
                Date.from(Instant.parse("2026-09-20T01:30:23Z")));
        TollTraversal row = new TraversalBuilder(registry).build(1, 20, 10, enter, exit, 3900, new Date(),
                () -> trail.subList(6, 13), null);
        assertEquals("a30", row.getFacilityId());
        assertEquals(0, new BigDecimal("4.40").compareTo(row.getAmount()), row.getInputs());
        assertNull(row.getReason());
    }
}

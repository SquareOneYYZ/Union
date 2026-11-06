package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.traccar.model.TollTraversal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TraversalBuilderTest {

    private static TraversalBuilder builder;
    private static final Date NOW = new Date();

    private static final TraversalBuilder.Edge AT_403 = new TraversalBuilder.Edge(
            11, 43.526442, -79.735966, Date.from(java.time.Instant.parse("2026-03-17T12:12:00Z")));
    private static final TraversalBuilder.Edge AT_400 = new TraversalBuilder.Edge(
            12, 43.783547, -79.535593, Date.from(java.time.Instant.parse("2026-03-17T12:40:00Z")));
    private static final TraversalBuilder.Edge ATLANTA = new TraversalBuilder.Edge(   // Georgia express lanes: not priced yet
            13, 33.85, -84.25, Date.from(java.time.Instant.parse("2026-03-17T12:40:00Z")));
    private static final TraversalBuilder.Edge SR408_WEST = new TraversalBuilder.Edge(
            21, 28.5482, -81.505, Date.from(java.time.Instant.parse("2026-09-17T12:00:00Z")));
    private static final TraversalBuilder.Edge SR408_EAST = new TraversalBuilder.Edge(
            22, 28.5482, -81.430, Date.from(java.time.Instant.parse("2026-09-17T12:05:00Z")));

    @BeforeAll
    public static void load() throws Exception {
        FacilityRegistry registry = FacilityRegistry.loadResource();
        assertEquals(1, registry.getFacilities().size());
        assertEquals(4, registry.getGantrySets().size());
        builder = new TraversalBuilder(registry);
    }

    @Test
    public void pricedRowCarriesFacilityAmountAndInputs() {
        TollTraversal row = builder.build(7, 105, 101, AT_403, AT_400, 42300, NOW);
        assertEquals("407-etr", row.getFacilityId());
        assertEquals(0, new BigDecimal("42.89").compareTo(row.getAmount()));
        assertEquals("CAD", row.getCurrency());
        assertEquals("eastbound", row.getDirection());
        assertNull(row.getReason());
        assertEquals(TollEstimate.CALC_VERSION, row.getCalcVersion());
        assertEquals("light", row.getVehicleClass());
        assertTrue(row.getInputs().contains("\"zoneKm\""));
        assertTrue(row.getInputs().contains("\"osmBase\""));
    }

    @Test
    public void exitWithoutEnterIsRecordedUnpriced() {
        TollTraversal row = builder.build(7, 105, 0, null, AT_400, 0, NOW);
        assertNull(row.getAmount());
        assertEquals(TraversalBuilder.REASON_NO_ENTER_EVENT, row.getReason());
        assertEquals(105, row.getExitEventId());
    }

    @Test
    public void missingPositionIsRecordedUnpriced() {
        TollTraversal row = builder.build(7, 105, 101, AT_403, null, 0, NOW);
        assertEquals(TraversalBuilder.REASON_NO_POSITION, row.getReason());
    }

    @Test
    public void cfxTraversalIsPricedFromItsTrail() {
        List<PassageDetector.TrailPoint> trail = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            trail.add(new PassageDetector.TrailPoint(28.5482, -81.505 + i * 0.0025,
                    new Date(SR408_WEST.fixTime().getTime() + i * 10_000L)));
        }
        TollTraversal row = builder.build(7, 105, 101, SR408_WEST, SR408_EAST, 7300, NOW, () -> trail, null);
        assertEquals("cfx", row.getFacilityId());
        assertEquals("USD", row.getCurrency());
        assertEquals(0, new BigDecimal("4.80").compareTo(row.getAmount()));
        assertTrue(row.getInputs().contains("\"onRampM\""));
    }

    @Test
    public void cfxTraversalWithoutTrailIsUnknown() {
        TollTraversal row = builder.build(7, 105, 101, SR408_WEST, SR408_EAST, 7300, NOW, () -> null, null);
        assertNull(row.getAmount());
        assertEquals(FlatPricer.REASON_NO_TRAIL, row.getReason());
    }

    @Test
    public void unregisteredFacilityIsRecordedUnpriced() {
        TollTraversal row = builder.build(7, 105, 101, ATLANTA, ATLANTA, 5000, NOW);
        assertNull(row.getAmount());
        assertNull(row.getFacilityId());
        assertEquals(TraversalBuilder.REASON_NO_FACILITY, row.getReason());
    }
}

package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.traccar.model.TollTraversal;

import java.math.BigDecimal;
import java.util.Date;

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
    private static final TraversalBuilder.Edge ORLANDO = new TraversalBuilder.Edge(
            13, 28.4497, -81.3076, Date.from(java.time.Instant.parse("2026-03-17T12:40:00Z")));

    @BeforeAll
    public static void load() throws Exception {
        FacilityRegistry registry = FacilityRegistry.loadResource();
        assertEquals(1, registry.getFacilities().size());
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
    public void unregisteredFacilityIsRecordedUnpriced() {
        TollTraversal row = builder.build(7, 105, 101, ORLANDO, ORLANDO, 5000, NOW);
        assertNull(row.getAmount());
        assertNull(row.getFacilityId());
        assertEquals(TraversalBuilder.REASON_NO_FACILITY, row.getReason());
    }
}

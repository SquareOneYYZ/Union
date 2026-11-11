package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end over the packaged 407 corridor and card. Interchange coordinates are the generated
 * corridor's (exit 24 = Hwy 403, 28 = Britannia, 65 = Weston, 66 = Hwy 400, 102 = Whites, 105 = Brock).
 * Expected totals come from {@code price_407_reference.py} on the same zone km.
 */
public class DistanceZonedPricerTest {

    private static final double[] EXIT_24 = {43.526442, -79.735966};
    private static final double[] EXIT_28 = {43.552119, -79.763247};
    private static final double[] EXIT_65 = {43.778475, -79.557889};
    private static final double[] EXIT_66 = {43.783547, -79.535593};
    private static final double[] EXIT_102 = {43.904711, -79.145702};
    private static final double[] EXIT_105 = {43.91473, -79.103255};

    private static final Set<String> ONTARIO_STATUTORY = Set.of("NEW_YEAR", "FAMILY_DAY", "christian.GOOD_FRIDAY",
            "VICTORIA_DAY", "CANADA_DAY", "LABOUR_DAY", "THANKSGIVING", "CHRISTMAS", "BOXING_DAY");

    private static ZonedCorridor corridor;
    private static OperatorHolidays holidays;
    private static DistanceZonedPricer pricer;

    @BeforeAll
    public static void load() throws Exception {
        corridor = ZonedCorridor.loadResource("407-etr.json");
        holidays = new OperatorHolidays("ca", "on", ONTARIO_STATUTORY);
        pricer = new DistanceZonedPricer(DistanceZonedCard.loadResource("407-etr-2026-light.json"), corridor,
                ZoneId.of("America/Toronto"), holidays);
    }

    private static double[] between(double[] a, double[] b, double f) {
        return new double[] {a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])};
    }

    @Test
    public void eastboundSnapsOutwardAndMatchesReference() throws Exception {
        double[] entry = between(EXIT_24, EXIT_28, 0.05);   // detected just past Hwy 403
        double[] exit = between(EXIT_66, EXIT_65, 0.15);    // detected just before Hwy 400
        DistanceZonedPricer.Result r = pricer.price(entry[0], entry[1],
                Instant.parse("2026-03-17T12:12:00Z"), exit[0], exit[1]); // 08:12 EDT, Tuesday

        assertEquals("eastbound", r.inputs().get("direction"));
        assertEquals("24", r.inputs().get("entryExit"));
        assertEquals("66", r.inputs().get("exitExit"));
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("Z4", 9.1);
        expected.put("Z5", 13.4);
        expected.put("Z6", 11.6);
        expected.put("Z7", 8.2);
        assertEquals(expected, r.inputs().get("zoneKm"));
        assertEquals("7 a.m.", r.estimate().getBand());
        assertEquals(0, new BigDecimal("42.89").compareTo(r.estimate().getAmount()));
    }

    @Test
    public void westboundOnFamilyDayIsHolidayPriced() throws Exception {
        DistanceZonedPricer.Result r = pricer.price(EXIT_66[0], EXIT_66[1],
                Instant.parse("2026-02-16T13:12:00Z"), EXIT_24[0], EXIT_24[1]); // 08:12 EST
        assertEquals("westbound", r.inputs().get("direction"));
        assertEquals(true, r.inputs().get("holiday"));
        assertEquals(DistanceZonedCard.DAY_WEEKEND_OR_HOLIDAY, r.estimate().getDayType());
        assertEquals(0, new BigDecimal("27.68").compareTo(r.estimate().getAmount()));
    }

    @Test
    public void pastBrockIsFlaggedMixedOperators() throws Exception {
        double[] east = between(EXIT_105, EXIT_102, -0.5);  // onto the 407 East extension
        DistanceZonedPricer.Result r = pricer.price(EXIT_102[0], EXIT_102[1],
                Instant.parse("2026-03-17T16:00:00Z"), east[0], east[1]);
        assertEquals(true, r.inputs().get("mixedOperators"));
        assertEquals("105", r.inputs().get("exitExit"));
    }

    // Highway 407 East junctions (OSM relation 6328033, motorway_junction refs 112, 127, 145).
    private static final double[] EAST_112 = {43.94931, -79.02184};
    private static final double[] EAST_127 = {43.98021, -78.87715};
    private static final double[] EAST_145 = {44.00385, -78.65991};

    private static DistanceZonedPricer registryPricer() throws Exception {
        return FacilityRegistry.loadResource().getFacilities().stream()
                .filter(f -> f.id().equals("407-etr")).findFirst().orElseThrow().pricer();
    }

    @Test
    public void wholly407EastIsNotBillableFromJune2025() throws Exception {
        DistanceZonedPricer p = registryPricer();
        DistanceZonedPricer.Result r = p.price(EAST_112[0], EAST_112[1],
                Instant.parse("2026-09-21T12:00:00Z"), EAST_145[0], EAST_145[1]);
        assertNull(r.estimate().getAmount());
        assertEquals(TollEstimate.REASON_NOT_BILLABLE, r.estimate().getReason());
        assertEquals("407-east", r.facilityId());
        assertTrue((Double) r.inputs().get("entryKm") > corridor.getEndKm(), r.inputs().toString());
        // Westbound too.
        r = p.price(EAST_127[0], EAST_127[1], Instant.parse("2026-09-21T12:00:00Z"), EAST_112[0], EAST_112[1]);
        assertEquals(TollEstimate.REASON_NOT_BILLABLE, r.estimate().getReason());
    }

    @Test
    public void wholly407EastBeforeTheTollsCameOffIsUnknown() throws Exception {
        DistanceZonedPricer.Result r = registryPricer().price(EAST_112[0], EAST_112[1],
                Instant.parse("2025-05-31T12:00:00Z"), EAST_145[0], EAST_145[1]);
        assertNull(r.estimate().getAmount());
        assertEquals(TollEstimate.REASON_NO_RATE, r.estimate().getReason());
        assertEquals("407-east", r.facilityId());
    }

    @Test
    public void etrOnto407EastIsPricedToBrock() throws Exception {
        DistanceZonedPricer p = registryPricer();
        DistanceZonedPricer.Result onto = p.price(EXIT_66[0], EXIT_66[1],
                Instant.parse("2026-03-17T16:00:00Z"), EAST_145[0], EAST_145[1]);
        DistanceZonedPricer.Result toBrock = p.price(EXIT_66[0], EXIT_66[1],
                Instant.parse("2026-03-17T16:00:00Z"), EXIT_105[0], EXIT_105[1]);
        assertEquals("105", onto.inputs().get("exitExit"));
        assertEquals(true, onto.inputs().get("mixedOperators"));
        assertNull(onto.facilityId());
        assertEquals(0, toBrock.estimate().getAmount().compareTo(onto.estimate().getAmount()), onto.inputs().toString());
        // Before 2025-06-01 the MTO portion was tolled and has no card: unknown, not the ETR part alone.
        DistanceZonedPricer.Result before = p.price(EXIT_66[0], EXIT_66[1],
                Instant.parse("2025-05-20T16:00:00Z"), EAST_145[0], EAST_145[1]);
        assertEquals(TollEstimate.REASON_NO_RATE, before.estimate().getReason());
    }

    @Test
    public void offCorridorIsUnknownNotZero() throws Exception {
        DistanceZonedPricer.Result r = pricer.price(43.6532, -79.3832,  // downtown Toronto
                Instant.parse("2026-03-17T16:00:00Z"), EXIT_66[0], EXIT_66[1]);
        assertNull(r.estimate().getAmount());
        assertEquals(DistanceZonedPricer.REASON_OFF_CORRIDOR, r.estimate().getReason());
    }

    @Test
    public void ontarioStatutoryHolidaysOnly() {
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 2, 16)));    // Family Day
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 4, 3)));     // Good Friday
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 12, 26)));   // Boxing Day
        assertFalse(holidays.isHoliday(LocalDate.of(2026, 4, 6)));    // Easter Monday: not statutory
        assertFalse(holidays.isHoliday(LocalDate.of(2026, 8, 3)));    // Civic Holiday: not statutory
        assertFalse(holidays.isHoliday(LocalDate.of(2026, 11, 11)));  // Remembrance Day: not statutory
        assertTrue(holidays.isHoliday(LocalDate.of(2029, 7, 1)));     // Canada Day on a Sunday: actual date
        assertFalse(holidays.isHoliday(LocalDate.of(2029, 7, 2)));    // no substitute Monday assumed
    }

    @Test
    public void misspelledHolidayKeyFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperatorHolidays("ca", "on", Set.of("FAMILYDAY")));
    }
}

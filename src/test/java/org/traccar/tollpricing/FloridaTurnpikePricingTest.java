package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Florida's Turnpike over the packaged card, TOLL-BY-PLATE 2-axle. Leesburg Plaza (Turnpike MP 288,
 * 28.6693 N, -81.8413): 2.00. Beachline West Plaza (28.4440 N, -81.3848) is one gantry where CFX
 * charges 1.38 and the Turnpike 1.16 - the published total is 2.54.
 */
public class FloridaTurnpikePricingTest {

    private static List<GantrySet> sets;

    @BeforeAll
    public static void load() throws Exception {
        sets = FacilityRegistry.loadResource().getGantrySets();
    }

    private static FlatPricer.Result through(double lat, double lon, String first, String second) {
        List<PassageDetector.TrailPoint> trail = List.of(
                new PassageDetector.TrailPoint(lat - 0.002, lon - 0.002, Date.from(Instant.parse(first))),
                new PassageDetector.TrailPoint(lat + 0.002, lon + 0.002, Date.from(Instant.parse(second))));
        return FlatPricer.price(trail, sets, trail.get(0).fixTime());
    }

    @Test
    public void leesburgPlazaIsTollByPlate() {
        FlatPricer.Result r = through(28.669256, -81.841307, "2026-09-21T15:00:00Z", "2026-09-21T15:00:20Z");
        assertEquals("fte", r.facilityId());
        assertEquals(0, new BigDecimal("2.00").compareTo(r.estimate().getAmount()), r.inputs().toString());
        assertEquals("2026-08-14", r.estimate().getCardValidFrom());
    }

    @Test
    public void theSharedBeachlineWestPlazaChargesBothPortions() {
        FlatPricer.Result r = through(28.44402, -81.384804, "2026-09-21T15:00:00Z", "2026-09-21T15:00:20Z");
        assertEquals(0, new BigDecimal("2.54").compareTo(r.estimate().getAmount()), r.inputs().toString());
        Map<?, ?> breakdown = (Map<?, ?>) r.inputs().get("breakdown");
        assertEquals(0, new BigDecimal("1.38").compareTo((BigDecimal) breakdown.get("cfx")));
        assertEquals(0, new BigDecimal("1.16").compareTo((BigDecimal) breakdown.get("fte")));
        assertEquals("cfx", r.facilityId());   // the larger portion names the row
    }

    @Test
    public void beachlineWestMatchesTheSchedulesPrintedSplitAndTotal() throws IOException {
        // The FTE schedule prints the CFX portion and the plaza total beside the Turnpike's portion; the builder
        // keeps them on the row. CFX's own card must charge exactly the printed CFX portion, and the priced
        // passage must equal the printed total.
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode fte = mapper.readTree(
                FloridaTurnpikePricingTest.class.getResourceAsStream("/toll-pricing/cards/fte-2026-08-14.json"));
        com.fasterxml.jackson.databind.JsonNode cfx = mapper.readTree(
                FloridaTurnpikePricingTest.class.getResourceAsStream("/toll-pricing/cards/cfx-2026-07-01.json"));
        com.fasterxml.jackson.databind.JsonNode check = null;
        for (com.fasterxml.jackson.databind.JsonNode p : fte.path("plazas")) {
            if (p.has("shared_plaza_check")) {
                check = p.path("shared_plaza_check");
            }
        }
        BigDecimal cfxCard = null;
        for (com.fasterxml.jackson.databind.JsonNode p : cfx.path("plazas")) {
            if (p.path("name").asText().equals("Beachline West Main Plaza (TPK)")) {
                cfxCard = p.path("pay_by_plate").path("2_axle").decimalValue();
            }
        }
        assertEquals(0, check.path("cfx_portion").path("toll_by_plate").path("2_axle").decimalValue()
                .compareTo(cfxCard));
        FlatPricer.Result r = through(28.44402, -81.384804, "2026-09-21T15:00:00Z", "2026-09-21T15:00:20Z");
        assertEquals(0, check.path("published_total").path("toll_by_plate").path("2_axle").decimalValue()
                .compareTo(r.estimate().getAmount()), r.inputs().toString());
    }

    @Test
    public void aSharedPlazaIsChargedOncePerFacilityAcrossSegments() {
        List<PassageDetector.TrailPoint> trail = List.of(
                new PassageDetector.TrailPoint(28.4430, -81.3858, Date.from(Instant.parse("2026-09-21T15:00:00Z"))),
                new PassageDetector.TrailPoint(28.44402, -81.384804, Date.from(Instant.parse("2026-09-21T15:00:05Z"))),
                new PassageDetector.TrailPoint(28.4450, -81.3838, Date.from(Instant.parse("2026-09-21T15:00:10Z"))));
        assertEquals(2, PassageDetector.detect(trail, sets).size());
    }

    /** A trail through {@code points} ([lat, lon]), one fix every {@code stepS} seconds from {@code start}. */
    private static FlatPricer.Result drive(String start, int stepS, double[]... points) {
        List<PassageDetector.TrailPoint> trail = new java.util.ArrayList<>();
        Instant t = Instant.parse(start);
        for (double[] p : points) {
            trail.add(new PassageDetector.TrailPoint(p[0], p[1], Date.from(t)));
            t = t.plusSeconds(stepS);
        }
        return FlatPricer.price(trail, sets, trail.get(0).fixTime());
    }

    private static void assertAmount(String expected, FlatPricer.Result r) {
        assertEquals(0, new BigDecimal(expected).compareTo(r.estimate().getAmount()), r.inputs().toString());
    }

    @Test
    public void alligatorAlleyIsChargedOncePerCrossingAtTheEndThatChargesThatWay() {
        // FDOT map (South Florida 08/2026): West Plaza takes eastbound tolls, East Plaza westbound; 3.51 plate.
        double[][] westbound = {{26.1462, -80.4620}, {26.1462, -80.4710}, {26.1555, -81.6650}, {26.1555, -81.6720}};
        assertAmount("3.51", drive("2026-09-21T15:00:00Z", 30, westbound));
        double[][] eastbound = {westbound[3], westbound[2], westbound[1], westbound[0]};
        assertAmount("3.51", drive("2026-09-21T15:00:00Z", 30, eastbound));
    }

    @Test
    public void theSunshineSkywayIsChargedOncePerCrossing() {
        // North Plaza takes southbound tolls, South Plaza northbound; 1.62 plate each way.
        double[][] southbound = {{27.6840, -82.6784}, {27.6780, -82.6784}, {27.5838, -82.6000}, {27.5838, -82.5930}};
        assertAmount("1.62", drive("2026-09-21T15:00:00Z", 30, southbound));
        double[][] northbound = {southbound[3], southbound[2], southbound[1], southbound[0]};
        assertAmount("1.62", drive("2026-09-21T15:00:00Z", 30, northbound));
    }

    @Test
    public void jitterWhileStoppedBesideAOneDirectionPlazaProvesNoDirection() {
        // Westbound, stopped in traffic 30 m from the West Plaza (eastbound tolls); the fixes wander 4-8 m
        // east, which as headings would read eastbound.
        double[][] westbound = {{26.1462, -80.4620}, {26.1462, -80.4710}, {26.1555, -81.6650},
                {26.15528, -81.66870}, {26.15528, -81.66866}, {26.15529, -81.66860}, {26.15528, -81.66852},
                {26.1555, -81.6720}};
        assertAmount("3.51", drive("2026-09-21T15:00:00Z", 30, westbound));
    }

    @Test
    public void anOrdinaryPlazaPassedTwiceTheSameWayIsChargedTwice() {
        // Leesburg northbound, off the Turnpike and round on local roads, Leesburg northbound again 10 min later.
        double[][] loop = {{28.6670, -81.8432}, {28.6716, -81.8395}, {28.6900, -81.8300}, {28.6900, -81.8600},
                {28.6670, -81.8432}, {28.6716, -81.8395}};
        assertAmount("4.00", drive("2026-09-21T15:00:00Z", 120, loop));
    }

    @Test
    public void dallasPlazaTotalsTheFdotMapsPlateRate() {
        // FDOT Beachline East map (08/2026): Dallas Main Plaza, 2 axles, TOLL-BY-PLATE $1.84 = CFX 1.28 + FDOT 0.56.
        FlatPricer.Result r = through(28.45206, -81.057923, "2026-09-21T15:00:00Z", "2026-09-21T15:00:20Z");
        assertAmount("1.84", r);
    }

    @Test
    public void aPlazaMappedAtTwoSitesIsChargedOnceEachWay() {
        // Lecanto is mapped 2.8 km apart; a northbound through trip at ~58 km/h passes both sites ~3 min apart,
        // past the 120 s debounce, so only the same-direction rule stops a second charge.
        double[][] north = {{28.8240, -82.5163}, {28.8270, -82.5163}, {28.8495, -82.5164}, {28.8525, -82.5164}};
        assertAmount("1.45", drive("2026-09-21T15:00:00Z", 90, north));
        // Coming back south through the first site is a second toll.
        double[][] uTurn = {north[0], north[1], north[2], north[3], {28.8270, -82.51635}, {28.8240, -82.51635}};
        assertAmount("2.90", drive("2026-09-21T15:00:00Z", 90, uTurn));
    }

    @Test
    public void beforeTheRowsOwnEffectiveDateIsUnknown() {
        // Leesburg's table (Central Florida) is effective 2026-08-14.
        FlatPricer.Result r = through(28.669256, -81.841307, "2026-08-01T15:00:00Z", "2026-08-01T15:00:20Z");
        assertNull(r.estimate().getAmount());
        assertEquals(FlatPricer.REASON_NO_RATE, r.estimate().getReason());
    }

    @Test
    public void aRowIsPricedFromItsOwnTablesDateNotTheCardsNewest() {
        // Beachline West's Turnpike portion is from a table effective 2026-07-01, so a CFX trip through the
        // shared plaza on 2026-07-15 is priced (CFX's own card is also 2026-07-01), not NO_RATE.
        FlatPricer.Result r = through(28.44402, -81.384804, "2026-07-15T15:00:00Z", "2026-07-15T15:00:20Z");
        assertEquals(0, new BigDecimal("2.54").compareTo(r.estimate().getAmount()), r.inputs().toString());
        // One day before both tables: unknown, never a later card's price.
        r = through(28.44402, -81.384804, "2026-06-30T15:00:00Z", "2026-06-30T15:00:20Z");
        assertEquals(FlatPricer.REASON_NO_RATE, r.estimate().getReason());
    }

    @Test
    public void aRowReachingBackOverAnEarlierCardStartsTheDayAfterIt() throws IOException {
        // A reprice republishes unchanged tables with their old dates: the earlier card keeps its days.
        String older = "{\"rate_model_type\":\"flat\",\"currency\":\"USD\",\"valid_from\":\"2026-01-01\",\"plazas\":["
                + "{\"road\":\"R\",\"name\":\"A\",\"toll_by_plate\":{\"2_axle\":1.00}}]}";
        String newer = "{\"rate_model_type\":\"flat\",\"currency\":\"USD\",\"valid_from\":\"2026-07-01\",\"plazas\":["
                + "{\"road\":\"R\",\"name\":\"A\",\"effective\":\"2025-12-01\",\"toll_by_plate\":{\"2_axle\":2.00}}]}";
        String gantries = "{\"card_valid_from\":\"2026-07-01\",\"plazas\":[{\"road\":\"R\",\"name\":\"A\","
                + "\"plaza_type\":\"mainline\",\"clusters\":[{\"lat\":28,\"lon\":-81}]}]}";
        GantrySet set = GantrySet.load("t", List.of(json(older), json(newer)), json(gantries),
                "toll_by_plate", "2_axle", true, ZoneId.of("America/New_York"));
        GantrySet.Point p = set.getPoints().get(0);
        assertNull(set.rateAt(p, Date.from(Instant.parse("2025-12-15T17:00:00Z"))));
        assertEquals(0, new BigDecimal("1.00").compareTo(
                set.rateAt(p, Date.from(Instant.parse("2026-01-01T17:00:00Z"))).amount()));
        assertEquals(0, new BigDecimal("2.00").compareTo(
                set.rateAt(p, Date.from(Instant.parse("2026-01-02T17:00:00Z"))).amount()));
    }

    @Test
    public void aTripFromTheTurnpikeOntoCfxTestsBothFacilities() throws IOException {
        // Enters at Leesburg (outside CFX's extent), leaves on SR 528 east of Beachline West.
        FacilityRegistry registry = FacilityRegistry.loadResource();
        List<String> ids = registry.flatNear(28.669256, -81.841307, 28.4460, -81.3600).stream()
                .map(GantrySet::getFacilityId).toList();
        assertTrue(ids.contains("fte"), ids.toString());
        assertTrue(ids.contains("cfx"), ids.toString());
        // And from a point near only the Turnpike, its partner (CFX) is still tested.
        ids = registry.flatNear(26.892344, -80.135204, 26.764631, -80.133632).stream()
                .map(GantrySet::getFacilityId).toList();
        assertTrue(ids.contains("cfx"), ids.toString());
    }

    @Test
    public void aSharedGantryWithoutItsPartnersPointsFailsLoudly() {
        List<GantrySet> fteOnly = sets.stream().filter(s -> s.getFacilityId().equals("fte")).toList();
        List<PassageDetector.TrailPoint> trail = List.of(
                new PassageDetector.TrailPoint(28.44202, -81.386804, Date.from(Instant.parse("2026-09-21T15:00:00Z"))),
                new PassageDetector.TrailPoint(28.44602, -81.382804, Date.from(Instant.parse("2026-09-21T15:00:20Z"))));
        assertThrows(IllegalStateException.class, () -> PassageDetector.detect(trail, fteOnly));
    }

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void linkingTwiceAddsNoDuplicatePartners() throws IOException {
        List<GantrySet> fresh = FacilityRegistry.loadResource().getGantrySets();
        GantrySet fte = fresh.stream().filter(s -> s.getFacilityId().equals("fte")).findFirst().orElseThrow();
        GantrySet.linkShared(fresh);
        for (GantrySet.Point p : fte.getPoints()) {
            if (p.sharedWith() != null) {
                assertEquals(1, fte.partnersOf(p).size(), p.plaza());
            }
        }
    }

    @Test
    public void aSharedPlazaWithoutItsPartnerFailsTheLoad() throws IOException {
        String card = "{\"rate_model_type\":\"flat\",\"currency\":\"USD\",\"valid_from\":\"2026-01-01\",\"plazas\":["
                + "{\"road\":\"R\",\"name\":\"A\",\"toll_by_plate\":{\"2_axle\":1.00}}]}";
        String gantries = "{\"card_valid_from\":\"2026-01-01\",\"plazas\":[{\"road\":\"R\",\"name\":\"A\","
                + "\"plaza_type\":\"mainline\",\"clusters\":[{\"lat\":28,\"lon\":-81,"
                + "\"shared_with\":{\"facility\":\"cfx\",\"plaza\":\"SR 528|Nowhere\"}}]}]}";
        GantrySet set = GantrySet.load("t", List.of(json(card)), json(gantries), "toll_by_plate", "2_axle", true,
                ZoneId.of("America/New_York"));
        List<GantrySet> withCfx = new java.util.ArrayList<>(FacilityRegistry.loadResource().getGantrySets());
        withCfx.add(set);
        assertThrows(IOException.class, () -> GantrySet.linkShared(withCfx));
    }
}

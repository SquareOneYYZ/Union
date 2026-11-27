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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Illinois Tollway over the packaged 2026 and 2027 cards. The Tri-State runs north-south past Touhy
 * Avenue mainline (42.0019 N, -87.8669) and Irving Park Road mainline (41.9628 N, -87.8766).
 * Invoice (unregistered plate), passenger: Touhy 4.90 (2026: Pay Online 1.90 + $3 fee) / 3.00 (2027);
 * Irving Park 4.50 / 2.40.
 */
public class IsthaPricingTest {

    private static List<GantrySet> sets;

    @BeforeAll
    public static void load() throws Exception {
        sets = FacilityRegistry.loadResource().getGantrySets();
    }

    private static PassageDetector.TrailPoint fix(double lat, double lon, String time) {
        return new PassageDetector.TrailPoint(lat, lon, Date.from(Instant.parse(time)));
    }

    /** Southbound past Touhy only, through the plaza between two fixes a minute apart. */
    private static FlatPricer.Result touhyAt(String first, String second) {
        List<PassageDetector.TrailPoint> trail = List.of(
                fix(42.0090, -87.866928, first), fix(41.9950, -87.866928, second));
        return FlatPricer.price(trail, sets, trail.get(0).fixTime());
    }

    @Test
    public void chargesThe2026CardBeforeTheReprice() {
        FlatPricer.Result r = touhyAt("2026-11-17T15:00:00Z", "2026-11-17T15:01:00Z");
        assertEquals("istha", r.facilityId());
        assertEquals(0, new BigDecimal("4.90").compareTo(r.estimate().getAmount()));
        assertEquals("USD", r.estimate().getCurrency());
        assertEquals("2026-01-01", r.estimate().getCardValidFrom());
    }

    @Test
    public void chargesThe2027CardAfterTheReprice() {
        FlatPricer.Result r = touhyAt("2027-01-01T15:00:00Z", "2027-01-01T15:01:00Z");
        assertEquals(0, new BigDecimal("3.00").compareTo(r.estimate().getAmount()));
        assertEquals("2027-01-01", r.estimate().getCardValidFrom());
    }

    @Test
    public void theRepriceIsChicagoMidnightNotUtc() {
        // 05:30Z on 1 January is 23:30 on 31 December in Chicago: still the 2026 card.
        FlatPricer.Result r = touhyAt("2027-01-01T05:30:00Z", "2027-01-01T05:31:00Z");
        assertEquals(0, new BigDecimal("4.90").compareTo(r.estimate().getAmount()));
        assertEquals("2026-01-01", r.estimate().getCardValidFrom());
    }

    @Test
    public void aTripAcrossTheRepriceChargesEachPassageItsOwnCard() {
        List<PassageDetector.TrailPoint> trail = List.of(
                fix(42.0090, -87.866928, "2027-01-01T05:58:00Z"),    // 23:58 Chicago, north of Touhy
                fix(41.9950, -87.866928, "2027-01-01T05:59:00Z"),    // past Touhy before midnight
                fix(41.9700, -87.876624, "2027-01-01T06:03:00Z"),
                fix(41.9550, -87.876624, "2027-01-01T06:05:00Z"));   // past Irving Park after midnight
        FlatPricer.Result r = FlatPricer.price(trail, sets, trail.get(0).fixTime());
        assertEquals(2, ((List<?>) r.inputs().get("passages")).size());
        assertEquals(0, new BigDecimal("7.30").compareTo(r.estimate().getAmount()));   // 4.90 + 2.40
        assertEquals("2026-01-01", r.estimate().getCardValidFrom());
        assertTrue(((Map<?, ?>) r.inputs().get("cardsUsed")).containsKey("istha"));
    }

    @Test
    public void aPassageBeforeEveryCardIsUnknownNotZero() {
        FlatPricer.Result r = touhyAt("2025-06-01T15:00:00Z", "2025-06-01T15:01:00Z");
        assertNull(r.estimate().getAmount());
        assertEquals(FlatPricer.REASON_NO_RATE, r.estimate().getReason());
    }

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String card(String validFrom, String... plazas) {
        StringBuilder rows = new StringBuilder();
        for (String plaza : plazas) {
            rows.append(rows.length() > 0 ? "," : "").append("{\"road\":\"R\",\"name\":\"").append(plaza)
                    .append("\",\"pay_by_plate\":{\"passenger\":1.00}}");
        }
        return "{\"rate_model_type\":\"flat\",\"currency\":\"USD\",\"valid_from\":\"" + validFrom
                + "\",\"plazas\":[" + rows + "]}";
    }

    private static final String GANTRIES = "{\"card_valid_from\":\"2027-01-01\",\"plazas\":["
            + "{\"road\":\"R\",\"name\":\"A\",\"plaza_type\":\"mainline\",\"clusters\":[{\"lat\":41,\"lon\":-87}]},"
            + "{\"road\":\"R\",\"name\":\"B\",\"plaza_type\":\"mainline\",\"clusters\":[]}]}";

    private static GantrySet loadCards(String... cards) throws IOException {
        List<InputStream> streams = new ArrayList<>();
        for (String c : cards) {
            streams.add(json(c));
        }
        return GantrySet.load("t", streams, json(GANTRIES), "pay_by_plate", "passenger", true,
                ZoneId.of("America/Chicago"));
    }

    @Test
    public void matchingCardsLoad() throws IOException {
        GantrySet set = loadCards(card("2026-01-01", "A", "B"), card("2027-01-01", "A", "B"));
        assertEquals(List.of("2026-01-01", "2027-01-01"), set.getCards());
        assertEquals(List.of("R|B"), set.getUnlocated());
    }

    @Test
    public void aRepriceThatAddsOrDropsAPlazaFailsTheLoad() {
        assertThrows(IOException.class, () -> loadCards(card("2026-01-01", "A", "B"), card("2027-01-01", "A")));
        assertThrows(IOException.class,
                () -> loadCards(card("2026-01-01", "A", "B"), card("2027-01-01", "A", "B", "C")));
    }

    @Test
    public void gantriesBuiltAgainstAnUnloadedCardFailTheLoad() {
        assertThrows(IOException.class, () -> loadCards(card("2026-01-01", "A", "B")));
    }

    @Test
    public void twoCardsFromTheSameDayFailTheLoad() {
        assertThrows(IOException.class, () -> loadCards(card("2027-01-01", "A", "B"), card("2027-01-01", "A", "B")));
    }
}

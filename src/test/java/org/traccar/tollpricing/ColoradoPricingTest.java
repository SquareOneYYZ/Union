package org.traccar.tollpricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * E-470 and the Northwest Parkway over the packaged cards, License Plate Toll / non-ExpressToll.
 * E-470 runs north-south past Plaza B (39.6684 N, -104.7233): 4.60 at all hours. The Northwest
 * Parkway runs east-west past its mainline plaza (39.9802 N, -105.0410): 7.50 base + 1.35 processing
 * fee = 8.85 from 06:00 to 21:00 Denver time, 3.50 + 1.35 = 4.85 overnight.
 */
public class ColoradoPricingTest {

    private static List<GantrySet> sets;

    @BeforeAll
    public static void load() throws Exception {
        sets = FacilityRegistry.loadResource().getGantrySets();
    }

    private static PassageDetector.TrailPoint fix(double lat, double lon, String time) {
        return new PassageDetector.TrailPoint(lat, lon, Date.from(Instant.parse(time)));
    }

    private static FlatPricer.Result plazaB(String first, String second) {
        List<PassageDetector.TrailPoint> trail = List.of(
                fix(39.6760, -104.723251, first), fix(39.6610, -104.723251, second));
        return FlatPricer.price(trail, sets, trail.get(0).fixTime());
    }

    /** Westbound past the Northwest Parkway mainline plaza. */
    private static FlatPricer.Result nwpMainline(String first, String second) {
        List<PassageDetector.TrailPoint> trail = List.of(
                fix(39.980199, -105.0330, first), fix(39.980199, -105.0490, second));
        return FlatPricer.price(trail, sets, trail.get(0).fixTime());
    }

    private static void assertAmount(String expected, FlatPricer.Result r) {
        assertEquals(0, new BigDecimal(expected).compareTo(r.estimate().getAmount()),
                "charged " + r.estimate().getAmount() + " " + r.inputs().get("passages"));
    }

    @Test
    public void e470PlazaBIsTheLicensePlateRate() {
        FlatPricer.Result r = plazaB("2026-11-17T15:00:00Z", "2026-11-17T15:01:00Z");
        assertEquals("e470", r.facilityId());
        assertAmount("4.60", r);
        assertEquals("2025-01-01", r.estimate().getCardValidFrom());
    }

    @Test
    public void nwpDaytimeIncludesTheProcessingFee() {
        FlatPricer.Result r = nwpMainline("2026-11-17T22:00:00Z", "2026-11-17T22:01:00Z");   // 15:00 MST
        assertEquals("nwp", r.facilityId());
        assertAmount("8.85", r);
    }

    @Test
    public void nwpOvernightBandWrapsPastMidnight() {
        assertAmount("4.85", nwpMainline("2026-11-18T06:00:00Z", "2026-11-18T06:01:00Z"));   // 23:00 MST
        assertAmount("4.85", nwpMainline("2026-11-18T09:00:00Z", "2026-11-18T09:01:00Z"));   // 02:00 MST
    }

    @Test
    public void nwpBandIsDenverLocalTimeAcrossDaylightSaving() {
        // 05:59 MDT (summer, UTC-6) is still overnight; 06:00 MDT is day.
        assertAmount("4.85", nwpMainline("2026-07-15T11:58:00Z", "2026-07-15T11:59:00Z"));
        assertAmount("8.85", nwpMainline("2026-07-15T12:00:00Z", "2026-07-15T12:00:30Z"));
    }

    @Test
    public void beforeTheNwpCardIsUnknown() {
        FlatPricer.Result r = nwpMainline("2026-04-30T18:00:00Z", "2026-04-30T18:01:00Z");
        assertNull(r.estimate().getAmount());
        assertEquals(FlatPricer.REASON_NO_RATE, r.estimate().getReason());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void bandsParseAndWrap() throws IOException {
        var bands = GantrySet.bands(MAPPER.readTree(
                "[{\"from\":\"06:00\",\"until\":\"21:00\",\"amount\":8.85},"
                        + "{\"from\":\"21:00\",\"until\":\"06:00\",\"amount\":4.85}]"), "t");
        assertEquals(2, bands.size());
        assertEquals(0, new BigDecimal("8.85").compareTo(bands.get(LocalTime.of(6, 0))));
        assertEquals(0, new BigDecimal("1.25").compareTo(
                GantrySet.bands(MAPPER.readTree("1.25"), "t").get(LocalTime.MIDNIGHT)));
    }

    @Test
    public void bandsThatDoNotMeetFailTheLoad() {
        assertThrows(IOException.class, () -> GantrySet.bands(MAPPER.readTree(
                "[{\"from\":\"06:00\",\"until\":\"20:00\",\"amount\":8.85},"
                        + "{\"from\":\"21:00\",\"until\":\"06:00\",\"amount\":4.85}]"), "t"));
        assertThrows(IOException.class, () -> GantrySet.bands(MAPPER.readTree(
                "[{\"from\":\"06:00\",\"amount\":1},{\"from\":\"06:00\",\"amount\":2}]"), "t"));
        assertThrows(IOException.class, () -> GantrySet.bands(MAPPER.readTree("[]"), "t"));
        assertThrows(IOException.class, () -> GantrySet.bands(MAPPER.readTree(
                "[{\"from\":\"06:00\",\"days\":\"weekdays\",\"amount\":1}]"), "t"));
        assertThrows(IOException.class, () -> GantrySet.bands(MAPPER.readTree(
                "[{\"from\":\"6am\",\"amount\":1}]"), "t"));
    }
}

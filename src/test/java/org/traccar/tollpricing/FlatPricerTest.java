package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gantry-passage pricing over the packaged CFX card and plaza locations. SR 408 runs west-east at
 * about 28.548 N between Hiawassee Main (-81.4922), the Hiawassee Road ramp booth (-81.4739) and
 * Pine Hills Main (-81.4400). Pay-by-plate, 2 axles: Hiawassee Main 2.04, Pine Hills Main 2.76,
 * Conway Main 2.76.
 */
public class FlatPricerTest {

    private static List<GantrySet> sets;
    private static final long T0 = 1_773_000_000_000L;

    @BeforeAll
    public static void load() throws Exception {
        sets = FacilityRegistry.loadResource().getGantrySets();
        assertEquals(1, sets.size());
    }

    /** A straight drive between two longitudes at 100 km/h, one fix every {@code cadenceS} seconds. */
    private static List<PassageDetector.TrailPoint> drive(double lat, double lonFrom, double lonTo, int cadenceS) {
        double metresPerDeg = 111320 * Math.cos(Math.toRadians(lat));
        double step = Math.signum(lonTo - lonFrom) * (100 / 3.6 * cadenceS) / metresPerDeg;
        List<PassageDetector.TrailPoint> trail = new ArrayList<>();
        long t = T0;
        for (double lon = lonFrom; step > 0 ? lon <= lonTo : lon >= lonTo; lon += step) {
            trail.add(new PassageDetector.TrailPoint(lat, lon, new Date(t)));
            t += cadenceS * 1000L;
        }
        return trail;
    }

    @Test
    public void eastboundSr408ChargesBothMainlinesAndNotTheRampBooth() {
        for (int cadence : new int[] {5, 30}) {
            FlatPricer.Result r = FlatPricer.price(drive(28.5482, -81.505, -81.430, cadence), sets, new Date(T0));
            assertEquals("cfx", r.facilityId());
            assertEquals(0, new BigDecimal("4.80").compareTo(r.estimate().getAmount()), "cadence " + cadence);
            assertEquals("USD", r.estimate().getCurrency());
            assertEquals(2, ((List<?>) r.inputs().get("passages")).size());
        }
    }

    @Test
    public void outOfOrderAndDuplicateFixesCountOnce() {
        List<PassageDetector.TrailPoint> trail = drive(28.5482, -81.505, -81.430, 10);
        List<PassageDetector.TrailPoint> scrambled = new ArrayList<>(trail);
        scrambled.addAll(trail.subList(0, 20));
        Collections.shuffle(scrambled, new Random(37));
        FlatPricer.Result r = FlatPricer.price(scrambled, sets, new Date(T0));
        assertEquals(0, new BigDecimal("4.80").compareTo(r.estimate().getAmount()));
    }

    @Test
    public void crawlingPastAPlazaIsOnePassage() {
        List<PassageDetector.TrailPoint> trail = new ArrayList<>();
        double lon = -81.4935;
        for (int i = 0; i < 40; i++) {   // 40 fixes 3 s apart, jittering across Hiawassee Main
            trail.add(new PassageDetector.TrailPoint(28.5481, lon + (i % 2 == 0 ? 0.0004 : -0.0002) + i * 0.00005,
                    new Date(T0 + i * 3000L)));
        }
        FlatPricer.Result r = FlatPricer.price(trail, sets, new Date(T0));
        assertEquals(0, new BigDecimal("2.04").compareTo(r.estimate().getAmount()));
    }

    @Test
    public void conwayWestAndEastClustersAreOnePlaza() {
        FlatPricer.Result r = FlatPricer.price(drive(28.5395, -81.327, -81.315, 5), sets, new Date(T0));
        assertEquals(0, new BigDecimal("2.76").compareTo(r.estimate().getAmount()));
        Map<?, ?> breakdown = (Map<?, ?>) r.inputs().get("breakdown");
        assertEquals(1, breakdown.size());
    }

    @Test
    public void noPassageIsUnknownNotZero() {
        FlatPricer.Result r = FlatPricer.price(drive(28.60, -81.505, -81.430, 30), sets, new Date(T0));
        assertNull(r.estimate().getAmount());
        assertEquals(FlatPricer.REASON_NO_PASSAGES, r.estimate().getReason());
    }

    @Test
    public void missingTrailIsUnknown() {
        FlatPricer.Result r = FlatPricer.price(null, sets, new Date(T0));
        assertNull(r.estimate().getAmount());
        assertEquals(FlatPricer.REASON_NO_TRAIL, r.estimate().getReason());
    }
}

package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ramp plazas against the packaged CFX geometry (decision 2026-11-06: prove ramp use from the trail).
 * Through traffic must never be charged a ramp; a fix proven on a ramp must be.
 */
public class RampDetectionTest {

    private static List<GantrySet> sets;
    private static List<GantrySet.Point> ramps;
    private static final long T0 = 1_773_000_000_000L;

    @BeforeAll
    public static void load() throws Exception {
        sets = FacilityRegistry.loadResource().getGantrySets();
        ramps = new ArrayList<>();
        for (GantrySet.Point p : sets.get(0).getPoints()) {
            if (p.isRamp()) {
                ramps.add(p);
            }
        }
        assertTrue(ramps.size() >= 50, "ramp points loaded: " + ramps.size());
    }

    private static long rampPassages(List<PassageDetector.TrailPoint> trail) {
        return PassageDetector.detect(trail, sets).stream().filter(p -> p.point().isRamp()).count();
    }

    @Test
    public void throughTrafficOnEveryMainlineIsNeverChargedARamp() {
        List<String> charged = new ArrayList<>();
        for (GantrySet.Point ramp : ramps) {
            for (double[][] line : ramp.mainlineLines()) {
                List<PassageDetector.TrailPoint> trail = new ArrayList<>();
                long t = T0;
                for (double[] vertex : line) {
                    trail.add(new PassageDetector.TrailPoint(vertex[0], vertex[1], new Date(t)));
                    t += 5000;
                }
                if (rampPassages(trail) > 0) {
                    charged.add(ramp.plaza() + " @" + ramp.lat() + "," + ramp.lon());
                }
            }
        }
        assertTrue(charged.isEmpty(), "through traffic charged a ramp at: " + charged);
    }

    @Test
    public void aFixProvenOnTheRampIsCharged() {
        int provable = 0;
        int charged = 0;
        for (GantrySet.Point ramp : ramps) {
            PassageDetector.TrailPoint onRamp = null;
            for (double[][] line : ramp.rampLines()) {
                for (double[] v : line) {
                    PassageDetector.TrailPoint fix = new PassageDetector.TrailPoint(v[0], v[1], new Date(T0));
                    if (PassageDetector.distance(fix, ramp.mainlineLines()) >= PassageDetector.OFF_MAINLINE_M) {
                        onRamp = fix;
                    }
                }
            }
            if (onRamp == null) {
                continue;
            }
            provable++;
            List<PassageDetector.Passage> passages = PassageDetector.detect(List.of(onRamp,
                    new PassageDetector.TrailPoint(onRamp.lat(), onRamp.lon(), new Date(T0 + 1000))), sets);
            if (passages.stream().anyMatch(p -> p.point().isRamp() && p.point().plaza().equals(ramp.plaza()))) {
                charged++;
            }
        }
        assertTrue(provable >= 50, "provable ramps: " + provable);
        assertEquals(provable, charged);
    }

    @Test
    public void aRampFixIsChargedOnceNotPerFix() {
        GantrySet.Point ramp = ramps.get(0);
        List<PassageDetector.TrailPoint> trail = new ArrayList<>();
        long t = T0;
        for (double[][] line : ramp.rampLines()) {
            for (double[] v : line) {
                trail.add(new PassageDetector.TrailPoint(v[0], v[1], new Date(t)));
                t += 2000;
            }
        }
        long same = PassageDetector.detect(trail, sets).stream()
                .filter(p -> p.point().plaza().equals(ramp.plaza())).count();
        assertTrue(same <= 1, "charged " + same + " times");
    }
}

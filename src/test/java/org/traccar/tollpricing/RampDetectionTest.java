package org.traccar.tollpricing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ramp plazas against the packaged CFX and Illinois Tollway geometry (decision 2026-11-06: prove ramp use from the trail).
 * Through traffic must never be charged a ramp; a fix proven on a ramp must be.
 */
public class RampDetectionTest {

    private static List<GantrySet> sets;
    private static List<GantrySet.Point> ramps;
    private static final long T0 = 1_790_000_000_000L;   // 2026-09-21, inside the 2026-07-01 CFX card

    @BeforeAll
    public static void load() throws Exception {
        sets = FacilityRegistry.loadResource().getGantrySets();
        ramps = new ArrayList<>();
        for (GantrySet set : sets) {
            for (GantrySet.Point p : set.getPoints()) {
                if (p.isRamp()) {
                    ramps.add(p);
                }
            }
        }
        assertTrue(ramps.size() >= 150, "ramp points loaded: " + ramps.size());
    }

    private static long rampPassages(List<PassageDetector.TrailPoint> trail) {
        return PassageDetector.detect(trail, sets).stream().filter(p -> p.point().isRamp()).count();
    }

    /**
     * The Illinois Tollway prices these two as ramps, but their gantries stand on the I-80 carriageways
     * themselves (the Tri-State/I-80 junction): each one's "ramp" is a carriageway and the other
     * carriageway is part of its "mainline". A trail along one of them is on the other's road, and the
     * charge is right.
     */
    private static final Set<String> CARRIAGEWAY_PLAZAS = Set.of(
            "Tri-State Tollway|I-80, Westbound", "Tri-State Tollway|I-80, Eastbound");

    @Test
    public void throughTrafficOnEveryMainlineIsNeverChargedARamp() {
        List<String> charged = new ArrayList<>();
        for (GantrySet.Point ramp : ramps) {
            if (CARRIAGEWAY_PLAZAS.contains(ramp.plaza())) {
                continue;
            }
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
        List<String> missed = new ArrayList<>();
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
            if (passages.stream().noneMatch(p -> p.point().isRamp() && p.point().plaza().equals(ramp.plaza()))) {
                missed.add(ramp.plaza() + " @" + ramp.lat() + "," + ramp.lon() + " fix " + onRamp.lat() + ","
                        + onRamp.lon() + " charged " + passages.stream().map(p -> p.point().plaza()).toList());
            }
        }
        assertTrue(provable >= 150, "provable ramps: " + provable);
        assertTrue(missed.isEmpty(), "proven on the ramp but not charged: " + missed);
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

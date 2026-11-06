package org.traccar.tollpricing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the toll points a GPS trail passed (decision 23, gaps 3 and 8).
 *
 * <ul>
 *   <li>the trail is ordered by fix time and duplicate fixes dropped - 37 devices deliver most
 *       on-toll readings out of order on the wire;</li>
 *   <li>each segment between consecutive fixes is tested against the clustered toll points within
 *       {@link #THRESHOLD_M}; only the nearest counts (per-direction pairs are already one cluster);</li>
 *   <li>the same plaza is counted once within {@link #DEBOUNCE_S}, so a cluster straddling two
 *       segments is not charged twice.</li>
 *   <li>a ramp plaza is never found by distance to its booth - half of CFX's ramp booths lie within
 *       50 m of the mainline. It is charged only when a fix lies within {@link #ON_RAMP_M} of the
 *       ramp's own shape and at least {@link #OFF_MAINLINE_M} from the mainline's, i.e. the trail
 *       proves the vehicle was on the ramp. Sparse fixes can miss a ramp (estimate low); through
 *       traffic is never charged one.</li>
 * </ul>
 */
public final class PassageDetector {

    /** How close a trail segment must come to a toll point. Recorded in inputs; measure in the dry-run. */
    public static final double THRESHOLD_M = 50;

    public static final long DEBOUNCE_S = 120;

    /** A fix this close to a ramp's shape is on the ramp (GPS error included). */
    public static final double ON_RAMP_M = 20;

    /** ...provided it is at least this far from the mainline, where the two shapes have separated. */
    public static final double OFF_MAINLINE_M = 30;

    private static final double EARTH_RADIUS_M = 6371008.8;
    private static final double METRES_PER_DEG = 111320;

    public record TrailPoint(double lat, double lon, Date fixTime) {
    }

    public record Passage(GantrySet.Point point, Date time, double distanceM) {
    }

    private PassageDetector() {
    }

    public static List<Passage> detect(List<TrailPoint> trail, List<GantrySet> sets) {
        List<TrailPoint> ordered = new ArrayList<>();
        for (TrailPoint p : trail) {
            if (p.fixTime() != null) {
                ordered.add(p);
            }
        }
        ordered.sort(Comparator.comparing(TrailPoint::fixTime));

        List<Passage> passages = new ArrayList<>();
        Map<String, Date> lastByPlaza = new HashMap<>();
        double pad = THRESHOLD_M / METRES_PER_DEG * 2;
        for (int i = 1; i < ordered.size(); i++) {
            TrailPoint a = ordered.get(i - 1);
            TrailPoint b = ordered.get(i);
            if (!b.fixTime().after(a.fixTime())) {
                continue;
            }
            Envelope env = new Envelope(a.lon(), b.lon(), a.lat(), b.lat());
            env.expandBy(pad);
            double refLat = (a.lat() + b.lat()) / 2;
            LineSegment segment = new LineSegment(
                    toMeters(a.lat(), a.lon(), refLat), toMeters(b.lat(), b.lon(), refLat));
            GantrySet.Point best = null;
            double bestDist = THRESHOLD_M;
            double bestFactor = 0;
            for (GantrySet set : sets) {
                for (GantrySet.Point point : set.query(env)) {
                    Coordinate c = toMeters(point.lat(), point.lon(), refLat);
                    double d = segment.distance(c);
                    if (d <= bestDist) {
                        best = point;
                        bestDist = d;
                        bestFactor = Math.max(0, Math.min(1, segment.projectionFactor(c)));
                    }
                }
            }
            if (best == null) {
                continue;
            }
            Date time = new Date(a.fixTime().getTime()
                    + Math.round(bestFactor * (b.fixTime().getTime() - a.fixTime().getTime())));
            String key = best.facilityId() + "|" + best.plaza();
            Date last = lastByPlaza.get(key);
            if (last != null && time.getTime() - last.getTime() < DEBOUNCE_S * 1000) {
                continue;
            }
            lastByPlaza.put(key, time);
            passages.add(new Passage(best, time, bestDist));
        }
        passages.addAll(detectRamps(ordered, sets));
        passages.sort(Comparator.comparing(Passage::time));
        return passages;
    }

    private static List<Passage> detectRamps(List<TrailPoint> ordered, List<GantrySet> sets) {
        List<Passage> passages = new ArrayList<>();
        Map<String, Date> lastByPlaza = new HashMap<>();
        for (TrailPoint fix : ordered) {
            GantrySet.Point best = null;
            double bestDist = ON_RAMP_M;
            for (GantrySet set : sets) {
                for (GantrySet.Point ramp : set.queryRamps(fix.lat(), fix.lon())) {
                    double onRamp = distance(fix, ramp.rampLines());
                    if (onRamp <= bestDist && distance(fix, ramp.mainlineLines()) >= OFF_MAINLINE_M) {
                        best = ramp;
                        bestDist = onRamp;
                    }
                }
            }
            if (best == null) {
                continue;
            }
            String key = best.facilityId() + "|" + best.plaza();
            Date last = lastByPlaza.get(key);
            if (last != null && fix.fixTime().getTime() - last.getTime() < DEBOUNCE_S * 1000) {
                continue;
            }
            lastByPlaza.put(key, fix.fixTime());
            passages.add(new Passage(best, fix.fixTime(), bestDist));
        }
        return passages;
    }

    /** Metres from a fix to the nearest of a set of [lat, lon] polylines. */
    static double distance(TrailPoint fix, List<double[][]> lines) {
        Coordinate p = toMeters(fix.lat(), fix.lon(), fix.lat());
        double best = Double.MAX_VALUE;
        for (double[][] line : lines) {
            for (int i = 1; i < line.length; i++) {
                LineSegment s = new LineSegment(toMeters(line[i - 1][0], line[i - 1][1], fix.lat()),
                        toMeters(line[i][0], line[i][1], fix.lat()));
                best = Math.min(best, s.distance(p));
            }
        }
        return best;
    }

    private static Coordinate toMeters(double lat, double lon, double refLat) {
        return new Coordinate(Math.toRadians(lon) * Math.cos(Math.toRadians(refLat)) * EARTH_RADIUS_M,
                Math.toRadians(lat) * EARTH_RADIUS_M);
    }
}

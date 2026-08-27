package org.traccar.handler.toll;

import org.traccar.helper.DistanceCalculator;
import org.traccar.model.Position;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * A recorded route as a replayable position stream.
 *
 * <p><b>Provenance.</b> Extracted from the {@code tools/tollRouteSample*.py} injectors, which
 * are live position senders - three of them POST to a staging host at import time - so the
 * tuples were parsed out as text and the scripts are not committed and never imported. Each
 * fixture holds only what those scripts carry: device time, coordinate, speed in knots and
 * course. Everything else a position needs is synthesised here.
 *
 * <p>{@code tollRouteSample2.py}, {@code tollRouteSample3.py} and
 * {@code tollRouteSampleStaggered.py} are the <em>same 1000 points</em> - verified identical by
 * hash - differing only in device count and threading. They are one dataset, stored once.
 */
public final class RouteFixture {

    /**
     * 1000 points, 2025-03-20, Chicago to the Quad Cities: I-90 / I-88 westbound across the
     * Illinois Tollway. 12.0 s median gap, 122 km/h median speed, 256 m median step between
     * distinct coordinates.
     *
     * <p>The reason this is the primary fixture: it is <b>bimodal</b>. Run lengths of
     * consecutive gate passes are 1, 2, 3, ... 15, 24, 34 - long highway stretches where every
     * position clears 500 m and detection works normally, and dense stretches where it cannot.
     * One device, both regimes, so a single replay can assert the gate is a no-op on the long
     * runs and fatal on the short ones.
     */
    public static final String ILLINOIS = "/toll/route-illinois-1000.csv";

    /**
     * 284 points, 2025-03-03, Ontario. 4.0 s median gap, 135 km/h median speed, 145 m median
     * step between distinct coordinates - the same profile class as the 44-position field
     * export for device 5964, on a different route.
     *
     * <p>Its value is independence: before this fixture the diagnosis rested on one drive.
     */
    public static final String ONTARIO = "/toll/route-ontario-284.csv";

    /** The gate distance shipped in {@code tollRoute.minimalDistance}. */
    public static final double GATE_METRES = 500.0;

    private final List<Position> positions;

    private RouteFixture(List<Position> positions) {
        this.positions = positions;
    }

    public static RouteFixture load(String resource, long deviceId) {
        List<Position> positions = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try (InputStream stream = RouteFixture.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            String[] lines = new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n");
            double totalDistance = 1_000_000.0;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                String[] field = lines[i].split(",");
                Position position = new Position();
                position.setId(700_000_000L + i);
                position.setDeviceId(deviceId);
                position.setProtocol("osmand");
                position.setValid(true);
                position.setTime(format.parse(field[0]));
                position.setLatitude(Double.parseDouble(field[1]));
                position.setLongitude(Double.parseDouble(field[2]));
                position.setSpeed(Double.parseDouble(field[3]));
                position.setCourse(Double.parseDouble(field[4]));

                // DistanceHandler is unconditional in the chain and always writes these two;
                // the injectors do not carry them, so they are reconstructed from the geometry.
                double step = positions.isEmpty() ? 0.0 : DistanceCalculator.distance(
                        positions.get(positions.size() - 1).getLatitude(),
                        positions.get(positions.size() - 1).getLongitude(),
                        position.getLatitude(), position.getLongitude());
                totalDistance += step;
                position.set(Position.KEY_DISTANCE, step);
                position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);

                positions.add(position);
            }
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("cannot load " + resource, e);
        }
        return new RouteFixture(positions);
    }

    public List<Position> positions() {
        return positions;
    }

    public int size() {
        return positions.size();
    }

    /**
     * Replays the gate exactly as {@code PositionInfoHandler} does - haversine from the last
     * <em>lookup</em> point, reference reset on each pass, first position always passing because
     * the map has no entry for the device yet.
     *
     * <p>Derived rather than hardcoded so a change to the gate distance re-derives the
     * expectation instead of silently invalidating the test. Uses
     * {@link DistanceCalculator#distance} for the same reason: it is the function the handler
     * calls, and it uses an equatorial earth radius of 6378.137 km. A mean radius of 6371 km -
     * the obvious alternative - shifts the Illinois pass count by 2 and its longest run by 1.
     *
     * @return one flag per position, true where the gate would issue a lookup
     */
    public boolean[] gatePasses(double gateMetres) {
        boolean[] passes = new boolean[positions.size()];
        double[] reference = null;
        for (int i = 0; i < positions.size(); i++) {
            Position position = positions.get(i);
            if (reference == null || DistanceCalculator.distance(
                    reference[0], reference[1], position.getLatitude(), position.getLongitude()) >= gateMetres) {
                passes[i] = true;
                reference = new double[]{position.getLatitude(), position.getLongitude()};
            }
        }
        return passes;
    }

    /** Lengths of every maximal run of consecutive gate passes, in stream order. */
    public List<Integer> passRunLengths(double gateMetres) {
        List<Integer> runs = new ArrayList<>();
        int current = 0;
        for (boolean pass : gatePasses(gateMetres)) {
            if (pass) {
                current++;
            } else if (current > 0) {
                runs.add(current);
                current = 0;
            }
        }
        if (current > 0) {
            runs.add(current);
        }
        return runs;
    }

    /** Start index of the longest run of consecutive passes, or -1 if there is none. */
    public int longestRunStart(double gateMetres) {
        boolean[] passes = gatePasses(gateMetres);
        int best = 0;
        int bestStart = -1;
        int current = 0;
        for (int i = 0; i < passes.length; i++) {
            if (passes[i]) {
                current++;
                if (current > best) {
                    best = current;
                    bestStart = i - current + 1;
                }
            } else {
                current = 0;
            }
        }
        return bestStart;
    }

    /**
     * Pairs of adjacent positions sharing a fix time <em>and</em> a coordinate - what the
     * injectors recorded when the device re-sent a fix.
     */
    public int duplicatePairs() {
        int duplicates = 0;
        for (int i = 1; i < positions.size(); i++) {
            Position previous = positions.get(i - 1);
            Position position = positions.get(i);
            if (position.getFixTime().equals(previous.getFixTime())
                    && position.getLatitude() == previous.getLatitude()
                    && position.getLongitude() == previous.getLongitude()) {
                duplicates++;
            }
        }
        return duplicates;
    }
}

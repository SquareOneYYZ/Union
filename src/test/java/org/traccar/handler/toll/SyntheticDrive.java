package org.traccar.handler.toll;

import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Generates position streams with a chosen cadence, speed and geometry.
 *
 * <p>The field export covers one device profile - 3.9 s at 131 km/h. The failure the fix
 * addresses is worst at <em>low</em> speed, where a position travels least between fixes, and
 * that region has no field data at all. These streams cover it.
 */
public final class SyntheticDrive {

    private static final double EARTH_RADIUS_METRES = 6378137.0;
    private static final long EPOCH = 1_756_000_000_000L;

    private SyntheticDrive() {
    }

    /**
     * A straight run on a constant bearing.
     *
     * @param bearingDegrees direction of travel
     * @param speedKph       ground speed
     * @param cadenceSeconds interval between fixes
     * @param count          number of positions
     */
    public static List<Position> straightLine(
            long deviceId, double latitude, double longitude,
            double bearingDegrees, double speedKph, double cadenceSeconds, int count) {
        return curve(deviceId, latitude, longitude, bearingDegrees, 0.0, speedKph, cadenceSeconds, count);
    }

    /**
     * A constant-radius turn: the bearing advances by {@code turnPerFixDegrees} at every fix.
     *
     * <p>This is what separates a straight-line gate from an accumulated-distance gate. On a
     * straight road the two agree to within 0.1 % - measured across the field drive's eight
     * lookup intervals - so the export cannot distinguish them.
     */
    public static List<Position> curve(
            long deviceId, double latitude, double longitude,
            double bearingDegrees, double turnPerFixDegrees,
            double speedKph, double cadenceSeconds, int count) {

        List<Position> positions = new ArrayList<>(count);
        double stepMetres = speedKph / 3.6 * cadenceSeconds;
        double currentLatitude = latitude;
        double currentLongitude = longitude;
        double bearing = bearingDegrees;
        double totalDistance = 1_000_000.0;

        for (int i = 0; i < count; i++) {
            Position position = new Position();
            position.setId(900_000_000L + i);
            position.setDeviceId(deviceId);
            position.setProtocol("synthetic");
            position.setValid(true);
            position.setLatitude(currentLatitude);
            position.setLongitude(currentLongitude);
            position.setSpeed(speedKph / 1.852);
            position.setCourse(bearing);
            position.setTime(new Date(EPOCH + (long) (i * cadenceSeconds * 1000)));
            position.set(Position.KEY_DISTANCE, i == 0 ? 0.0 : stepMetres);
            position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
            positions.add(position);

            double[] next = advance(currentLatitude, currentLongitude, bearing, stepMetres);
            currentLatitude = next[0];
            currentLongitude = next[1];
            bearing += turnPerFixDegrees;
            totalDistance += stepMetres;
        }
        return positions;
    }

    /** Repeats one coordinate, advancing only the clock - a parked vehicle still reporting. */
    public static List<Position> stationary(long deviceId, Position at, int count) {
        List<Position> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Position position = new Position();
            position.setId(950_000_000L + i);
            position.setDeviceId(deviceId);
            position.setProtocol("synthetic");
            position.setValid(true);
            position.setLatitude(at.getLatitude());
            position.setLongitude(at.getLongitude());
            position.setSpeed(0.0);
            position.setTime(new Date(at.getFixTime().getTime() + (i + 1) * 30_000L));
            position.set(Position.KEY_DISTANCE, 0.0);
            position.set(Position.KEY_TOTAL_DISTANCE, at.getDouble(Position.KEY_TOTAL_DISTANCE));
            positions.add(position);
        }
        return positions;
    }

    /** Appends {@code count} further fixes continuing the last position's heading and cadence. */
    public static List<Position> continueFrom(List<Position> stream, double speedKph,
            double cadenceSeconds, int count) {
        Position last = stream.get(stream.size() - 1);
        List<Position> extension = curve(last.getDeviceId(), last.getLatitude(), last.getLongitude(),
                last.getCourse(), 0.0, speedKph, cadenceSeconds, count + 1);
        List<Position> tail = new ArrayList<>(extension.subList(1, extension.size()));
        double base = last.getDouble(Position.KEY_TOTAL_DISTANCE);
        double step = speedKph / 3.6 * cadenceSeconds;
        for (int i = 0; i < tail.size(); i++) {
            Position position = tail.get(i);
            position.setId(960_000_000L + i);
            position.setTime(new Date(last.getFixTime().getTime() + (long) ((i + 1) * cadenceSeconds * 1000)));
            position.set(Position.KEY_TOTAL_DISTANCE, base + (i + 1) * step);
        }
        return tail;
    }

    private static double[] advance(double latitude, double longitude, double bearingDegrees, double metres) {
        double angular = metres / EARTH_RADIUS_METRES;
        double bearing = Math.toRadians(bearingDegrees);
        double lat1 = Math.toRadians(latitude);
        double lon1 = Math.toRadians(longitude);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular)
                + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));
        double lon2 = lon1 + Math.atan2(Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
                Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
        return new double[]{Math.toDegrees(lat2), Math.toDegrees(lon2)};
    }
}

package org.traccar.tollroute.valhalla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


public final class DeviceTraceBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceTraceBuffer.class);

    public record TracePoint(double lat, double lon, long unixTimeSec) { }

    private static final double DEFAULT_TELEPORT_THRESHOLD_KM = 50.0;

    private final int    capacity;
    private final double teleportThresholdKm;

    private final ConcurrentHashMap<String, DeviceEntry> buffers = new ConcurrentHashMap<>();

    private static final class DeviceEntry {
        final List<TracePoint> points;
        final long             lastUpdatedMs;

        DeviceEntry(List<TracePoint> points, long lastUpdatedMs) {
            this.points        = points;
            this.lastUpdatedMs = lastUpdatedMs;
        }
    }

    public DeviceTraceBuffer(int capacity) {
        this(capacity, DEFAULT_TELEPORT_THRESHOLD_KM);
    }

    public DeviceTraceBuffer(int capacity, double teleportThresholdKm) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be ≥ 2 (Valhalla map_snap minimum)");
        }
        this.capacity            = capacity;
        this.teleportThresholdKm = teleportThresholdKm;
    }


    public List<TracePoint> add(long deviceId, double lat, double lon, long unixTimeSec) {
        String key = String.valueOf(deviceId);

        DeviceEntry result = buffers.compute(key, (k, existing) -> {
            long nowMs = System.currentTimeMillis();

            if (existing == null || existing.points.isEmpty()) {
                return new DeviceEntry(List.of(new TracePoint(lat, lon, unixTimeSec)), nowMs);
            }

            TracePoint last = existing.points.get(existing.points.size() - 1);

            if (unixTimeSec < last.unixTimeSec()) {
                LOGGER.debug("DeviceTraceBuffer: dropped out-of-order point for deviceId={} "
                        + "(t={} < last={})", deviceId, unixTimeSec, last.unixTimeSec());
                return existing;
            }

            double distKm = haversineKm(last.lat(), last.lon(), lat, lon);
            if (distKm > teleportThresholdKm) {
                LOGGER.debug("DeviceTraceBuffer: teleport detected for deviceId={} "
                        + "({:.1f} km) — resetting buffer", deviceId, distKm);
                return new DeviceEntry(List.of(new TracePoint(lat, lon, unixTimeSec)), nowMs);
            }

            List<TracePoint> updated = new ArrayList<>(existing.points);
            updated.add(new TracePoint(lat, lon, unixTimeSec));
            if (updated.size() > capacity) {
                updated.remove(0);
            }
            return new DeviceEntry(Collections.unmodifiableList(updated), nowMs);
        });

        return result.points;
    }


    public List<TracePoint> get(long deviceId) {
        DeviceEntry entry = buffers.get(String.valueOf(deviceId));
        return entry == null ? List.of() : entry.points;
    }


    public int evictIdle(long ttlMs) {
        long cutoff = System.currentTimeMillis() - ttlMs;
        int[] count = {0};
        buffers.entrySet().removeIf(e -> {
            if (e.getValue().lastUpdatedMs < cutoff) {
                count[0]++;
                return true;
            }
            return false;
        });
        if (count[0] > 0) {
            LOGGER.debug("DeviceTraceBuffer: evicted {} idle device buffer(s)", count[0]);
        }
        return count[0];
    }

    public int size() {
        return buffers.size();
    }


    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r    = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

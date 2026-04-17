/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.Position;
import org.traccar.model.ReplaySession;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

@Singleton
public class ReplaySessionService {

    private static final int SESSION_TTL_SECONDS = 3600;
    private static final String KEY_PREFIX = "replay:session:";

    private final RedisCache redisCache;
    private final Storage storage;
    private final ObjectMapper objectMapper;

    @Inject
    public ReplaySessionService(RedisCache redisCache, Storage storage, ObjectMapper objectMapper) {
        this.redisCache = redisCache;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }


    public ReplaySession createSession(long userId, long deviceId, Date from, Date to) throws StorageException {
        Condition condition = new Condition.And(
                new Condition.Equals("deviceId", deviceId),
                new Condition.Between("fixTime", "from", from, "to", to));
        long totalCount = storage.getCount(Position.class, condition);

        ReplaySession session = new ReplaySession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setDeviceId(deviceId);
        session.setFrom(from.getTime());
        session.setTo(to.getTime());
        session.setTotalCount(totalCount);
        session.setCreatedAt(System.currentTimeMillis());

        persistSession(session);
        return session;
    }


    public List<Position> getChunk(ReplaySession session, int offset, int limit) throws StorageException {
        refreshTtl(session.getId());

        Date from = new Date(session.getFrom());
        Date to = new Date(session.getTo());

        return storage.getObjects(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", session.getDeviceId()),
                        new Condition.Between("fixTime", "from", from, "to", to)),
                new Order("fixTime", false, limit, offset)));
    }

    public List<Position> getOverview(ReplaySession session, int limit) throws StorageException {
        refreshTtl(session.getId());

        if (limit <= 0) {
            return List.of();
        }

        Date from = new Date(session.getFrom());
        Date to = new Date(session.getTo());

        Condition baseCondition = new Condition.And(
                new Condition.Equals("deviceId", session.getDeviceId()),
                new Condition.Between("fixTime", "from", from, "to", to));

        List<Position> first = storage.getObjects(Position.class, new Request(
                new Columns.Include("latitude", "longitude", "fixTime"),
                baseCondition,
                new Order("fixTime", false, 1, 0)));
        if (first.isEmpty()) {
            return List.of();
        }

        int sampleLimit = Math.min(Math.max(limit * 50, 200), 2000);
        int bucketCount = Math.min(20, Math.max(1, (int) Math.ceil(sampleLimit / 100.0)));
        int bucketSize = (int) Math.ceil((double) sampleLimit / bucketCount);

        long fromMs = from.getTime();
        long toMs = to.getTime();
        long spanMs = Math.max(1, toMs - fromMs);

        List<Position> points = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            long startMs = fromMs + (spanMs * i) / bucketCount;
            long endMs = fromMs + (spanMs * (i + 1)) / bucketCount;
            Date bucketFrom = new Date(startMs);
            Date bucketTo = new Date(endMs);

            Condition bucketCondition = new Condition.And(
                    new Condition.Equals("deviceId", session.getDeviceId()),
                    new Condition.Between("fixTime", "from", bucketFrom, "to", bucketTo));

            points.addAll(storage.getObjects(Position.class, new Request(
                    new Columns.Include("latitude", "longitude", "fixTime"),
                    bucketCondition,
                    new Order("fixTime", false, bucketSize, 0))));
        }

        List<Position> last = storage.getObjects(Position.class, new Request(
                new Columns.Include("latitude", "longitude", "fixTime"),
                baseCondition,
                new Order("fixTime", true, 1, 0)));
        if (!last.isEmpty()) {
            points.add(last.get(0));
        }

        points.sort((a, b) -> a.getFixTime().compareTo(b.getFixTime()));

        List<Position> unique = new ArrayList<>(points.size());
        Position prev = null;
        for (Position p : points) {
            if (prev == null || !p.getFixTime().equals(prev.getFixTime())) {
                unique.add(p);
                prev = p;
            }
        }

        return simplifyRdpToLimit(unique, limit);
    }

    private static List<Position> simplifyRdpToLimit(List<Position> points, int limit) {
        if (points.size() <= limit || limit < 2) {
            return points;
        }

        double diag = boundingDiagonalMeters(points);
        double low = 0.0;
        double high = Math.max(1.0, diag);

        List<Position> best = points;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2.0;
            List<Position> simplified = simplifyRdp(points, mid);
            if (simplified.size() > limit) {
                low = mid;
            } else {
                best = simplified;
                high = mid;
            }
        }

        if (best.size() <= limit) {
            return best;
        }
        return decimateEvenly(points, limit);
    }

    private static List<Position> simplifyRdp(List<Position> points, double epsilonMeters) {
        int n = points.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;

        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {0, n - 1});

        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int start = range[0];
            int end = range[1];
            if (end <= start + 1) {
                continue;
            }

            int index = -1;
            double maxDistance = -1.0;
            Position a = points.get(start);
            Position b = points.get(end);

            for (int i = start + 1; i < end; i++) {
                double d = perpendicularDistanceMeters(points.get(i), a, b);
                if (d > maxDistance) {
                    maxDistance = d;
                    index = i;
                }
            }

            if (maxDistance > epsilonMeters && index != -1) {
                keep[index] = true;
                stack.push(new int[] {start, index});
                stack.push(new int[] {index, end});
            }
        }

        List<Position> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    private static List<Position> decimateEvenly(List<Position> points, int limit) {
        int n = points.size();
        if (limit >= n) {
            return points;
        }
        List<Position> result = new ArrayList<>(limit);
        double step = (double) (n - 1) / (double) (limit - 1);
        for (int i = 0; i < limit; i++) {
            int index = (int) Math.round(i * step);
            if (index >= n) {
                index = n - 1;
            }
            result.add(points.get(index));
        }
        return result;
    }

    private static double boundingDiagonalMeters(List<Position> points) {
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (Position p : points) {
            double lat = p.getLatitude();
            double lon = p.getLongitude();
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLon = Math.min(minLon, lon);
            maxLon = Math.max(maxLon, lon);
        }
        return haversineMeters(minLat, minLon, maxLat, maxLon);
    }

    private static double perpendicularDistanceMeters(Position p, Position a, Position b) {
        double lat0 = Math.toRadians((a.getLatitude() + b.getLatitude()) / 2.0);
        double x1 = lonToMeters(a.getLongitude(), lat0);
        double y1 = latToMeters(a.getLatitude());
        double x2 = lonToMeters(b.getLongitude(), lat0);
        double y2 = latToMeters(b.getLatitude());
        double x0 = lonToMeters(p.getLongitude(), lat0);
        double y0 = latToMeters(p.getLatitude());

        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0.0 && dy == 0.0) {
            return Math.hypot(x0 - x1, y0 - y1);
        }
        double t = ((x0 - x1) * dx + (y0 - y1) * dy) / (dx * dx + dy * dy);
        double xProj = x1 + t * dx;
        double yProj = y1 + t * dy;
        return Math.hypot(x0 - xProj, y0 - yProj);
    }

    private static double latToMeters(double latDeg) {
        return latDeg * 111_320.0;
    }

    private static double lonToMeters(double lonDeg, double latRad) {
        return lonDeg * 111_320.0 * Math.cos(latRad);
    }

    private static double haversineMeters(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double r = 6371000.0;
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }

    public ReplaySession getSession(String sessionId) {
        String json = redisCache.get(KEY_PREFIX + sessionId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ReplaySession.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void persistSession(ReplaySession session) throws StorageException {
        try {
            String json = objectMapper.writeValueAsString(session);
            redisCache.setWithTTL(KEY_PREFIX + session.getId(), json, SESSION_TTL_SECONDS);
        } catch (JsonProcessingException e) {
            throw new StorageException(e);
        }
    }

    private void refreshTtl(String sessionId) {
        redisCache.expire(KEY_PREFIX + sessionId, SESSION_TTL_SECONDS);
    }

}

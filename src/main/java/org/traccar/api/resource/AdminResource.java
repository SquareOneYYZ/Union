package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.database.StatisticsManager;
import org.traccar.model.Statistics;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Path("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource extends BaseResource {

    @Inject
    private DataSource dataSource;

    @Inject
    private Config config;

    @Inject
    private ObjectMapper objectMapper;

    @Inject
    private RedisCache redisCache;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private StatisticsManager statisticsManager;

    @GET
    @Path("health")
    public Map<String, Object> getHealth() throws StorageException {
        permissionsService.checkAdmin(getUserId());

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> dbPool = new LinkedHashMap<>();
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                dbPool.put("activeConnections", pool.getActiveConnections());
                dbPool.put("idleConnections", pool.getIdleConnections());
                dbPool.put("totalConnections", pool.getTotalConnections());
                dbPool.put("threadsAwaitingConnection", pool.getThreadsAwaitingConnection());
                dbPool.put("maximumPoolSize", hikari.getMaximumPoolSize());
                dbPool.put("status", "ok");
            } else {
                dbPool.put("status", "pool MXBean unavailable");
            }
        } else {
            dbPool.put("status", "non-Hikari datasource");
        }
        result.put("dbPool", dbPool);

        Map<String, Object> redis = new LinkedHashMap<>();
        boolean redisAvailable = redisCache.isAvailable();
        redis.put("available", redisAvailable);
        redis.put("status", redisAvailable ? "ok" : "unavailable");
        result.put("redis", redis);

        result.put("webSocketClients", connectionManager.getWebSocketClientCount());
        result.put("messagesStoredToday", statisticsManager.getMessagesStoredToday());
        result.put("serverTime", new Date());

        return result;
    }


    @GET
    @Path("devices/silent")
    public List<Map<String, Object>> getSilentDevices(
            @QueryParam("hours") @DefaultValue("24") int hours) throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        Date cutoff = new Date(System.currentTimeMillis() - (long) hours * 3_600_000L);

        String sql = "SELECT id, name, uniqueid, status, lastupdate, groupid"
                + " FROM tc_devices"
                + " WHERE lastupdate < :cutoff OR lastupdate IS NULL"
                + " ORDER BY name";

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            String parsed = sql.replace(":cutoff", "?");
            try (PreparedStatement stmt = conn.prepareStatement(parsed)) {
                stmt.setTimestamp(1, new Timestamp(cutoff.getTime()));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("name", rs.getString("name"));
                        row.put("uniqueId", rs.getString("uniqueid"));
                        row.put("status", rs.getString("status"));
                        Timestamp lu = rs.getTimestamp("lastupdate");
                        row.put("lastUpdate", lu != null ? new Date(lu.getTime()) : null);
                        row.put("groupId", rs.getLong("groupid"));
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }


    @GET
    @Path("devices/stuck")
    public List<Map<String, Object>> getStuckDevices(
            @QueryParam("hours") @DefaultValue("2") int hours) throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        Date cutoff = new Date(System.currentTimeMillis() - (long) hours * 3_600_000L);

        String latestSql = "SELECT d.id AS deviceid, d.name, d.uniqueid, d.groupid,"
                + " p.id AS posid, p.latitude AS lat, p.longitude AS lon, p.fixtime, p.servertime"
                + " FROM tc_devices d"
                + " JOIN tc_positions p ON p.id = d.positionid"
                + " WHERE d.status = 'online'";

        String prevSql = "SELECT latitude, longitude FROM tc_positions"
                + " WHERE deviceid = ? AND fixtime < ?"
                + " ORDER BY fixtime DESC LIMIT 1";

        List<Map<String, Object>> stuck = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(latestSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("deviceId", rs.getLong("deviceid"));
                    c.put("name", rs.getString("name"));
                    c.put("uniqueId", rs.getString("uniqueid"));
                    c.put("groupId", rs.getLong("groupid"));
                    c.put("latitude", rs.getDouble("lat"));
                    c.put("longitude", rs.getDouble("lon"));
                    Timestamp ft = rs.getTimestamp("fixtime");
                    c.put("fixTime", ft != null ? new Date(ft.getTime()) : null);
                    Timestamp st = rs.getTimestamp("servertime");
                    c.put("serverTime", st != null ? new Date(st.getTime()) : null);
                    candidates.add(c);
                }
            }

            try (PreparedStatement prevStmt = conn.prepareStatement(prevSql)) {
                for (Map<String, Object> candidate : candidates) {
                    long deviceId = (Long) candidate.get("deviceId");
                    double currentLat = (Double) candidate.get("latitude");
                    double currentLon = (Double) candidate.get("longitude");

                    prevStmt.setLong(1, deviceId);
                    prevStmt.setTimestamp(2, new Timestamp(cutoff.getTime()));

                    try (ResultSet rs = prevStmt.executeQuery()) {
                        if (rs.next()) {
                            double prevLat = rs.getDouble("latitude");
                            double prevLon = rs.getDouble("longitude");
                            if (Double.compare(currentLat, prevLat) == 0
                                    && Double.compare(currentLon, prevLon) == 0) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("id", deviceId);
                                entry.put("name", candidate.get("name"));
                                entry.put("uniqueId", candidate.get("uniqueId"));
                                entry.put("status", "online");
                                entry.put("latitude", currentLat);
                                entry.put("longitude", currentLon);
                                entry.put("fixTime", candidate.get("fixTime"));
                                entry.put("serverTime", candidate.get("serverTime"));
                                entry.put("groupId", candidate.get("groupId"));
                                stuck.add(entry);
                            }
                        }

                    }
                }
            }
        }
        return stuck;
    }


    @GET
    @Path("devices/orphan")
    public List<Map<String, Object>> getOrphanDevices() throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        String sql = "SELECT d.id, d.name, d.uniqueid, d.status"
                + " FROM tc_devices d"
                + " WHERE (d.groupid IS NULL OR d.groupid = 0)"
                + "   AND (d.positionid IS NULL OR d.positionid = 0)"
                + "   AND d.id NOT IN (SELECT deviceid FROM tc_device_driver)"
                + " ORDER BY d.name";

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("uniqueId", rs.getString("uniqueid"));
                row.put("status", rs.getString("status"));
                result.add(row);
            }
        }
        return result;
    }


    @GET
    @Path("devices/duplicate-vin")
    public List<Map<String, Object>> getDuplicateVin() throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        String vinSql = "SELECT UPPER(TRIM(vin)) AS normalizedVin, COUNT(*) AS cnt"
                + " FROM tc_devices"
                + " WHERE vin IS NOT NULL AND TRIM(vin) <> ''"
                + " GROUP BY UPPER(TRIM(vin))"
                + " HAVING COUNT(*) > 1"
                + " ORDER BY normalizedVin";

        String deviceSql = "SELECT id, name, uniqueid, organizationid"
                + " FROM tc_devices"
                + " WHERE UPPER(TRIM(vin)) = ?"
                + " ORDER BY id";

        List<Map<String, Object>> duplicates = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            List<String> duplicateVins = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(vinSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    duplicateVins.add(rs.getString("normalizedVin"));
                }
            }

            try (PreparedStatement devStmt = conn.prepareStatement(deviceSql)) {
                for (String vin : duplicateVins) {
                    devStmt.setString(1, vin);
                    List<Map<String, Object>> devices = new ArrayList<>();
                    try (ResultSet rs = devStmt.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("id", rs.getLong("id"));
                            d.put("name", rs.getString("name"));
                            d.put("uniqueId", rs.getString("uniqueid"));
                            d.put("organizationId", rs.getLong("organizationid"));
                            devices.add(d);
                        }
                    }
                    Map<String, Object> collision = new LinkedHashMap<>();
                    collision.put("vin", vin);
                    collision.put("count", devices.size());
                    collision.put("devices", devices);
                    duplicates.add(collision);
                }
            }
        }
        return duplicates;
    }


    @GET
    @Path("protocol-breakdown")
    public Map<String, Object> getProtocolBreakdown() throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Integer> liveProtocols = statisticsManager.getProtocolBreakdown();
        result.put("protocolBreakdown", liveProtocols);
        result.put("totalActiveDevices",
                liveProtocols.values().stream().mapToInt(Integer::intValue).sum());

        Date cutoff = new Date(System.currentTimeMillis() - 24L * 3_600_000L);
        String eventSql = "SELECT type, COUNT(*) AS cnt"
                + " FROM tc_events"
                + " WHERE eventtime > ?"
                + " GROUP BY type"
                + " ORDER BY cnt DESC";

        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        int totalEvents = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(eventSql)) {
            stmt.setTimestamp(1, new Timestamp(cutoff.getTime()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int cnt = rs.getInt("cnt");
                    eventCounts.put(rs.getString("type"), cnt);
                    totalEvents += cnt;
                }
            }
        }
        result.put("eventCountsByType", eventCounts);
        result.put("totalEvents", totalEvents);

        return result;
    }


    @GET
    @Path("positions/ingestion-rate")
    public Map<String, Object> getIngestionRate() throws StorageException {
        permissionsService.checkAdmin(getUserId());

        Map<String, Object> result = new LinkedHashMap<>();

        int todayCount = statisticsManager.getMessagesStoredToday();
        result.put("todayCount", todayCount);

        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long elapsedSeconds = Math.max(1, (now - cal.getTimeInMillis()) / 1000);

        double perSecond = (double) todayCount / elapsedSeconds;
        result.put("perSecond", Math.round(perSecond * 100.0) / 100.0);
        result.put("perHour", (long) Math.round(perSecond * 3600));
        result.put("elapsedSeconds", elapsedSeconds);

        Date from = new Date(now - 24L * 3_600_000L);
        Date to = new Date(now);
        List<Statistics> history = storage.getObjects(
                Statistics.class,
                new Request(
                        new Columns.All(),
                        new Condition.Between("captureTime", "from", from, "to", to),
                        new Order("captureTime")));

        List<Map<String, Object>> historical = new ArrayList<>();
        for (Statistics stat : history) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("captureTime", stat.getCaptureTime());
            entry.put("messagesStored", stat.getMessagesStored());
            historical.add(entry);
        }
        result.put("historical", historical);

        return result;
    }

}

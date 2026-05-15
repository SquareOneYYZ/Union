package org.traccar.api.resource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.traccar.api.BaseResource;
import org.traccar.database.StatisticsManager;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.Event;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.Statistics;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.StorageName;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource extends BaseResource {

    private static final String T_DEVICES  = Device.class.getAnnotation(StorageName.class).value();
    private static final String T_POSITIONS = Position.class.getAnnotation(StorageName.class).value();
    private static final String T_EVENTS   = Event.class.getAnnotation(StorageName.class).value();
    private static final String T_DEV_DRIVER = Permission.getStorageName(Device.class, Driver.class);

    @Inject
    private DataSource dataSource;

    @Inject
    private RedisCache redisCache;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private StatisticsManager statisticsManager;

    private static long elapsedSecondsSinceMidnight() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return Math.max(1, (System.currentTimeMillis() - cal.getTimeInMillis()) / 1000);
    }

    private static Date toDate(Timestamp ts) {
        return ts != null ? new Date(ts.getTime()) : null;
    }


    @GET
    @Path("health")
    public Map<String, Object> getHealth() throws StorageException, SQLException {
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
                dbPool.put("status", "mxbean_unavailable");
            }
        } else {
            dbPool.put("status", "non_hikari");
        }
        result.put("dbPool", dbPool);
        boolean redisUp = redisCache.isAvailable();
        result.put("redis", Map.of("available", redisUp, "status", redisUp ? "ok" : "unavailable"));
        result.put("webSocketClients", connectionManager.getWebSocketClientCount());

        int storedToday = statisticsManager.getMessagesStoredToday();
        if (storedToday > 0) {
            long elapsedSec = elapsedSecondsSinceMidnight();
            double lagSeconds = Math.round((double) elapsedSec / storedToday * 100.0) / 100.0;
            result.put("ingestionLag", Map.of(
                    "messagesStoredToday", storedToday,
                    "avgSecondsBetweenMessages", lagSeconds));
        } else {
            result.put("ingestionLag", null);
        }

        Date oneHourAgo = new Date(System.currentTimeMillis() - 3_600_000L);
        String errorSql = "SELECT COUNT(*) FROM " + T_EVENTS
                + " WHERE type = 'alarm' AND eventtime > ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(errorSql)) {
            stmt.setTimestamp(1, new Timestamp(oneHourAgo.getTime()));
            try (ResultSet rs = stmt.executeQuery()) {
                result.put("recentErrorCount", rs.next() ? rs.getLong(1) : 0L);
            }
        }

        result.put("serverTime", new Date());
        return result;
    }

    @GET
    @Path("devices/silent")
    public Map<String, Object> getSilentDevices(
            @QueryParam("hours")  @DefaultValue("24")  int hours,
            @QueryParam("limit")  @DefaultValue("500") int limit,
            @QueryParam("offset") @DefaultValue("0")   int offset) throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        Date cutoff = new Date(System.currentTimeMillis() - (long) hours * 3_600_000L);

        String countSql = "SELECT COUNT(*) FROM " + T_DEVICES
                + " WHERE lastupdate < ? OR lastupdate IS NULL";

        String dataSql = "SELECT id, name, uniqueid, status, lastupdate, groupid"
                + " FROM " + T_DEVICES
                + " WHERE lastupdate < ? OR lastupdate IS NULL"
                + " ORDER BY name"
                + " LIMIT ? OFFSET ?";

        List<Map<String, Object>> rows = new ArrayList<>();
        long total;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement cs = conn.prepareStatement(countSql)) {
                cs.setTimestamp(1, new Timestamp(cutoff.getTime()));
                try (ResultSet rs = cs.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement(dataSql)) {
                stmt.setTimestamp(1, new Timestamp(cutoff.getTime()));
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id",         rs.getLong("id"));
                        row.put("name",       rs.getString("name"));
                        row.put("uniqueId",   rs.getString("uniqueid"));
                        row.put("status",     rs.getString("status"));
                        row.put("lastUpdate", toDate(rs.getTimestamp("lastupdate")));
                        row.put("groupId",    rs.getLong("groupid"));
                        rows.add(row);
                    }
                }
            }
        }
        return Map.of("total", total, "limit", limit, "offset", offset, "data", rows);
    }


    @GET
    @Path("devices/stuck")
    public List<Map<String, Object>> getStuckDevices(
            @QueryParam("hours") @DefaultValue("2") int hours) throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        Date cutoff = new Date(System.currentTimeMillis() - (long) hours * 3_600_000L);
        String sql =
            "SELECT d.id, d.name, d.uniqueid, d.groupid,"
            + "     p_cur.latitude  AS cur_lat,"
            + "     p_cur.longitude AS cur_lon,"
            + "     p_cur.fixtime   AS cur_fixtime,"
            + "     p_cur.servertime AS cur_servertime"
            + " FROM " + T_DEVICES + " d"
            + " JOIN " + T_POSITIONS + " p_cur  ON p_cur.id = d.positionid"
            + " JOIN ("
            + "   SELECT deviceid, MAX(id) AS prev_id"
            + "   FROM " + T_POSITIONS
            + "   WHERE fixtime < ?"
            + "   GROUP BY deviceid"
            + " ) latest_prev ON latest_prev.deviceid = d.id"
            + " JOIN " + T_POSITIONS + " p_prev ON p_prev.id = latest_prev.prev_id"
            + " WHERE d.status = 'online'"
            + "   AND p_cur.id <> p_prev.id"
            + "   AND p_cur.latitude  = p_prev.latitude"
            + "   AND p_cur.longitude = p_prev.longitude"
            + " ORDER BY d.name";

        List<Map<String, Object>> stuck = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, new Timestamp(cutoff.getTime()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",         rs.getLong("id"));
                    row.put("name",       rs.getString("name"));
                    row.put("uniqueId",   rs.getString("uniqueid"));
                    row.put("status",     "online");
                    row.put("latitude",   rs.getDouble("cur_lat"));
                    row.put("longitude",  rs.getDouble("cur_lon"));
                    row.put("fixTime",    toDate(rs.getTimestamp("cur_fixtime")));
                    row.put("serverTime", toDate(rs.getTimestamp("cur_servertime")));
                    row.put("groupId",    rs.getLong("groupid"));
                    stuck.add(row);
                }
            }
        }
        return stuck;
    }


    @GET
    @Path("devices/orphan")
    public Map<String, Object> getOrphanDevices(
            @QueryParam("limit")  @DefaultValue("500") int limit,
            @QueryParam("offset") @DefaultValue("0")   int offset) throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        String countSql =
            "SELECT COUNT(*) FROM " + T_DEVICES + " d"
            + " LEFT JOIN " + T_DEV_DRIVER + " dd ON dd.deviceid = d.id"
            + " WHERE (d.groupid IS NULL OR d.groupid = 0)"
            + "   AND (d.positionid IS NULL OR d.positionid = 0)"
            + "   AND dd.deviceid IS NULL";

        String dataSql =
            "SELECT d.id, d.name, d.uniqueid, d.status"
            + " FROM " + T_DEVICES + " d"
            + " LEFT JOIN " + T_DEV_DRIVER + " dd ON dd.deviceid = d.id"
            + " WHERE (d.groupid IS NULL OR d.groupid = 0)"
            + "   AND (d.positionid IS NULL OR d.positionid = 0)"
            + "   AND dd.deviceid IS NULL"
            + " ORDER BY d.name"
            + " LIMIT ? OFFSET ?";

        List<Map<String, Object>> rows = new ArrayList<>();
        long total;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement cs = conn.prepareStatement(countSql);
                 ResultSet rs = cs.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }
            try (PreparedStatement stmt = conn.prepareStatement(dataSql)) {
                stmt.setInt(1, limit);
                stmt.setInt(2, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id",       rs.getLong("id"));
                        row.put("name",     rs.getString("name"));
                        row.put("uniqueId", rs.getString("uniqueid"));
                        row.put("status",   rs.getString("status"));
                        rows.add(row);
                    }
                }
            }
        }
        return Map.of("total", total, "limit", limit, "offset", offset, "data", rows);
    }


    @GET
    @Path("devices/duplicate-vin")
    public Map<String, Object> getDuplicateVin(
            @QueryParam("limit")  @DefaultValue("200") int limit,
            @QueryParam("offset") @DefaultValue("0")   int offset) throws StorageException, SQLException {
        permissionsService.checkAdmin(getUserId());

        String aggSql =
            "SELECT UPPER(TRIM(vin)) AS nvin,"
            + "     COUNT(*) AS device_count,"
            + "     COUNT(DISTINCT organizationid) AS org_count"
            + " FROM " + T_DEVICES
            + " WHERE vin IS NOT NULL AND TRIM(vin) <> ''"
            + " GROUP BY UPPER(TRIM(vin))"
            + " HAVING COUNT(DISTINCT organizationid) > 1"
            + " ORDER BY nvin"
            + " LIMIT ? OFFSET ?";

        String detailSql =
            "SELECT d.id, d.name, d.uniqueid, d.organizationid,"
            + "     UPPER(TRIM(d.vin)) AS nvin"
            + " FROM " + T_DEVICES + " d"
            + " WHERE UPPER(TRIM(d.vin)) IN ("
            + "   SELECT UPPER(TRIM(vin)) FROM " + T_DEVICES
            + "   WHERE vin IS NOT NULL AND TRIM(vin) <> ''"
            + "   GROUP BY UPPER(TRIM(vin))"
            + "   HAVING COUNT(DISTINCT organizationid) > 1"
            + " )"
            + " ORDER BY nvin, d.id";

        String countSql =
            "SELECT COUNT(*) FROM ("
            + "  SELECT 1 FROM " + T_DEVICES
            + "  WHERE vin IS NOT NULL AND TRIM(vin) <> ''"
            + "  GROUP BY UPPER(TRIM(vin))"
            + "  HAVING COUNT(DISTINCT organizationid) > 1"
            + ") sub";

        long total;
        Map<String, Map<String, Object>> byVin = new LinkedHashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement cs = conn.prepareStatement(countSql);
                 ResultSet rs = cs.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }

            try (PreparedStatement aggStmt = conn.prepareStatement(aggSql)) {
                aggStmt.setInt(1, limit);
                aggStmt.setInt(2, offset);
                try (ResultSet rs = aggStmt.executeQuery()) {
                    while (rs.next()) {
                        String nvin = rs.getString("nvin");
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("vin",         nvin);
                        entry.put("deviceCount", rs.getInt("device_count"));
                        entry.put("orgCount",    rs.getInt("org_count"));
                        entry.put("devices",     new ArrayList<Map<String, Object>>());
                        byVin.put(nvin, entry);
                    }
                }
            }

            if (!byVin.isEmpty()) {
                try (PreparedStatement detailStmt = conn.prepareStatement(detailSql);
                     ResultSet rs = detailStmt.executeQuery()) {
                    while (rs.next()) {
                        String nvin = rs.getString("nvin");
                        Map<String, Object> entry = byVin.get(nvin);
                        if (entry != null) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> devices =
                                    (List<Map<String, Object>>) entry.get("devices");
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("id",             rs.getLong("id"));
                            d.put("name",           rs.getString("name"));
                            d.put("uniqueId",       rs.getString("uniqueid"));
                            d.put("organizationId", rs.getLong("organizationid"));
                            devices.add(d);
                        }
                    }
                }
            }
        }

        return Map.of(
                "total",  total,
                "limit",  limit,
                "offset", offset,
                "data",   new ArrayList<>(byVin.values()));
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
        result.put("windowNote",
                "protocolBreakdown counts devices active since the last midnight rollover only");

        Date cutoff = new Date(System.currentTimeMillis() - 24L * 3_600_000L);
        String eventSql =
            "SELECT type, COUNT(*) AS cnt"
            + " FROM " + T_EVENTS
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
        long elapsedSeconds = elapsedSecondsSinceMidnight();
        double perSecond = (double) todayCount / elapsedSeconds;

        result.put("todayCount",     todayCount);
        result.put("elapsedSeconds", elapsedSeconds);
        result.put("perSecond",      Math.round(perSecond * 100.0) / 100.0);
        result.put("perHour",        (long) Math.round(perSecond * 3600));
        result.put("windowNote",
                "todayCount reflects messages stored since the last midnight rollover only");

        long now = System.currentTimeMillis();
        List<Statistics> history = storage.getObjects(
                Statistics.class,
                new Request(
                        new Columns.All(),
                        new Condition.Between(
                                "captureTime", "from", new Date(now - 24L * 3_600_000L), "to", new Date(now)),
                        new Order("captureTime")));

        List<Map<String, Object>> historical = new ArrayList<>();
        for (Statistics stat : history) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("captureTime",    stat.getCaptureTime());
            entry.put("messagesStored", stat.getMessagesStored());
            historical.add(entry);
        }
        result.put("historical", historical);

        return result;
    }

}

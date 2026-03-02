/*
 * Copyright 2026 RidesIQ
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
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.storage.StorageException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Path("archive")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ArchiveResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveResource.class);

    private static final double MIN_SPEED_KNOTS = 0.5;
    private static final long MAX_GAP_SECONDS = 5 * 60;
    private static final long MIN_TRIP_DURATION_SECONDS = 60;
    private static final double MIN_TRIP_DISTANCE_METRES = 100.0;
    private static final long MIN_STOP_DURATION_SECONDS = 5 * 60;

    @Inject
    private Config config;


    private List<String> buildS3CmdBase() {
        String pythonExe = config.getString(Keys.ARCHIVE_PYTHON_EXE);
        String s3cmdScript = config.getString(Keys.ARCHIVE_S3CMD_SCRIPT);

        if (pythonExe == null || pythonExe.isBlank()) {
            throw new WebApplicationException(
                    Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .entity("{\"error\":\"archive.python.exe not configured in debug.xml\"}")
                            .build());
        }
        if (s3cmdScript == null || s3cmdScript.isBlank()) {
            throw new WebApplicationException(
                    Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .entity("{\"error\":\"archive.s3cmd.script not configured in debug.xml\"}")
                            .build());
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExe);
        cmd.add(s3cmdScript);
        return cmd;
    }

    private String requireBucket() {
        String bucket = config.getString(Keys.ARCHIVE_SPACES_BUCKET);
        if (bucket == null || bucket.isBlank()) {
            throw new WebApplicationException(
                    Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .entity("{\"error\":\"archive.spaces.bucket not configured\"}")
                            .build());
        }
        return bucket;
    }

    private List<String> runProcess(List<String> command) throws IOException, InterruptedException {
        LOGGER.info("Running command: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOGGER.info("STDOUT: {}", line);
                lines.add(line);
            }
        }

        StringBuilder stderr = new StringBuilder();
        try (BufferedReader errReader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream()))) {
            String line;
            while ((line = errReader.readLine()) != null) {
                LOGGER.error("STDERR: {}", line);
                stderr.append(line).append("\n");
            }
        }

        int exitCode = proc.waitFor();
        LOGGER.info("Process exit code: {}", exitCode);
        if (exitCode != 0) {
            throw new RuntimeException("s3cmd failed: " + stderr);
        }
        return lines;
    }

    @GET
    @Path("list")
    public Response listArchiveFiles(@QueryParam("prefix") String prefix) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        String bucket = requireBucket();
        String s3Url = "s3://" + bucket + "/" + (prefix == null ? "" : prefix);

        List<String> cmd = buildS3CmdBase();
        cmd.add("ls");
        cmd.add("--recursive");
        cmd.add(s3Url);

        List<String> lines;
        try {
            lines = runProcess(cmd);
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (Exception e) {
            LOGGER.error("s3cmd ls failed", e);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity("{\"error\":\"" + e.getMessage() + "\"}")
                            .build());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        String bucketPrefix = "s3://" + bucket + "/";

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 4);
            if (parts.length < 4) {
                continue;
            }
            try {
                String lastModified = parts[0] + " " + parts[1]; // "2024-07-01 12:34"
                long size = Long.parseLong(parts[2]);
                String fullS3Url = parts[3];
                String key = fullS3Url.startsWith(bucketPrefix)
                        ? fullS3Url.substring(bucketPrefix.length())
                        : fullS3Url;

                Map<String, Object> entry = new HashMap<>();
                entry.put("key", key);
                entry.put("size", size);
                entry.put("lastModified", lastModified);
                result.add(entry);
            } catch (NumberFormatException ignored) {
            }
        }

        return Response.ok(result).build();
    }


    @GET
    @Path("records")
    public Response getArchiveRecords(
            @QueryParam("key") String key,
            @QueryParam("type") String type,
            @QueryParam("deviceId") Integer deviceId,
            @QueryParam("from") String from,
            @QueryParam("to") String to) throws StorageException {
        LOGGER.info("=== ARCHIVE RECORDS API CALLED ===");
        LOGGER.info("Incoming key: {}", key);
        permissionsService.checkAdmin(getUserId());
        LOGGER.info("Permission check passed");

        if (key == null || key.isBlank()) {
            LOGGER.error("Key is null or blank");
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":\"'key' query parameter is required\"}")
                            .build());
        }

        java.time.LocalDateTime fromDt = null;
        java.time.LocalDateTime toDt = null;

        java.time.format.DateTimeFormatter isoFormatter =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        java.time.format.DateTimeFormatter dbFormatter =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try {
            if (from != null && !from.isBlank()) {
                fromDt = java.time.LocalDateTime.parse(from, isoFormatter);
            }
            if (to != null && !to.isBlank()) {
                toDt = java.time.LocalDateTime.parse(to, isoFormatter);
            }
        } catch (Exception e) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":\"Invalid date format. Use ISO format: 2025-10-09T22:34:44Z\"}")
                            .build());
        }

        final java.time.LocalDateTime finalFrom = fromDt;
        final java.time.LocalDateTime finalTo = toDt;

        String bucket = requireBucket();
        LOGGER.info("Using bucket: {}", bucket);
        String s3Url = "s3://" + bucket + "/" + key;
        LOGGER.info("Full S3 URL: {}", s3Url);

        String tmpFileName = "archive_" + UUID.randomUUID() + ".parquet";
        File tmpFile = new File(System.getProperty("java.io.tmpdir"), tmpFileName);
        LOGGER.info("Temp file path: {}", tmpFile.getAbsolutePath());

        try {
            List<String> cmd = buildS3CmdBase();
            cmd.add("get");
            cmd.add("--force");
            cmd.add(s3Url);
            cmd.add(tmpFile.getAbsolutePath());
            LOGGER.info("Executing s3cmd: {}", String.join(" ", cmd));

            runProcess(cmd);

            LOGGER.info("Download completed");
            LOGGER.info("Temp file exists: {}", tmpFile.exists());
            LOGGER.info("Temp file size: {}", tmpFile.exists() ? tmpFile.length() : -1);

            LOGGER.info("Starting parquet read...");

            List<Map<String, Object>> records = readParquetFile(tmpFile);
            if (type != null && !type.isBlank()) {
                records = records.stream()
                        .filter(row -> type.equals(String.valueOf(row.get("type"))))
                        .collect(java.util.stream.Collectors.toList());
                LOGGER.info("After type filter '{}': {} records", type, records.size());
            }
            if (deviceId != null) {
                records = records.stream()
                        .filter(row -> {
                            Object val = row.get("deviceid");
                            return val != null && Integer.parseInt(String.valueOf(val)) == deviceId;
                        })
                        .collect(java.util.stream.Collectors.toList());
                LOGGER.info("After deviceId filter '{}': {} records", deviceId, records.size());
            }

            if (finalFrom != null || finalTo != null) {
                records = records.stream()
                        .filter(row -> {
                            Object val = row.get("eventtime");
                            if (val == null) {
                                return false;
                            }
                            try {
                                java.time.LocalDateTime rowDt =
                                        java.time.LocalDateTime.parse(
                                                String.valueOf(val).trim(), dbFormatter);
                                if (finalFrom != null && rowDt.isBefore(finalFrom)) {
                                    return false;
                                }
                                if (finalTo != null && rowDt.isAfter(finalTo)) {
                                    return false;
                                }
                                return true;
                            } catch (Exception e) {
                                LOGGER.warn("Could not parse eventtime: {}", val);
                                return false;
                            }
                        })
                        .collect(java.util.stream.Collectors.toList());
                LOGGER.info("After time filter from={} to={}: {} records", finalFrom, finalTo, records.size());
            }

            LOGGER.info("Parquet read completed. Total records: {}", records.size());

            return Response.ok(records).build();
            } catch (Exception e) {
                e.printStackTrace(); // VERY IMPORTANT
                LOGGER.error("Archive read failed", e);

                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("error", e.getMessage()))
                        .build();


        } finally {
            LOGGER.info("Entering finally block");
            if (tmpFile.exists()) {
                try {
                    Files.delete(tmpFile.toPath());
                    LOGGER.info("Temp file deleted");
                } catch (IOException e) {
                    LOGGER.warn("Could not delete temp file: {}", tmpFile.getAbsolutePath());
                }
            }
            LOGGER.info("=== ARCHIVE RECORDS API END ===");
        }
    }


    private List<Map<String, Object>> readParquetFile(File file) {
        List<Map<String, Object>> records = new ArrayList<>();
        String hadoopHome = config.getString(Keys.ARCHIVE_HADOOP_HOME);
        if (hadoopHome == null || hadoopHome.isBlank()) {
            throw new WebApplicationException(
                    Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .entity("{\"error\":\"archive.hadoop.home not configured in debug.xml\"}")
                            .build());
        }
        System.setProperty("hadoop.home.dir", hadoopHome);

        Configuration hadoopConf = new Configuration();
        hadoopConf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
        hadoopConf.set("fs.file.impl.disable.cache", "true");
        hadoopConf.setBoolean("mapreduce.job.user.classpath.first", false);

        org.apache.hadoop.fs.Path hadoopPath =
                new org.apache.hadoop.fs.Path(file.getAbsolutePath());

        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(HadoopInputFile.fromPath(hadoopPath, hadoopConf))
                .build()) {

            GenericRecord record;
            while ((record = reader.read()) != null) {
                Map<String, Object> row = new HashMap<>();
                for (org.apache.avro.Schema.Field field : record.getSchema().getFields()) {
                    Object value = record.get(field.name());
                    if (value instanceof org.apache.avro.util.Utf8) {
                        value = value.toString();
                    }
                    row.put(field.name(), value);
                }
                records.add(row);
            }

        } catch (IOException e) {
//            e.printStackTrace();
            LOGGER.error("Failed to read Parquet file: {}", file.getAbsolutePath(), e);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity("{\"error\":\"Failed to read Parquet file: " + e.getMessage() + "\"}")
                            .build());
        }

        return records;
    }



    @GET
    @Path("trips")
    public Response getArchiveTrips(
            @QueryParam("deviceId") Integer deviceId,
            @QueryParam("from") String from,
            @QueryParam("to") String to) throws StorageException {

        LOGGER.info("=== ARCHIVE TRIPS API CALLED === deviceId={} from={} to={}", deviceId, from, to);
        permissionsService.checkAdmin(getUserId());

        if (deviceId == null) {
            return badRequest("'deviceId' query parameter is required");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return badRequest("'from' and 'to' query parameters are required");
        }

        DateTimeFormatter isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        LocalDateTime fromDt, toDt;
        try {
            fromDt = LocalDateTime.parse(from, isoFormatter);
            toDt   = LocalDateTime.parse(to,   isoFormatter);
        } catch (Exception e) {
            return badRequest("Invalid date format. Use: 2025-01-01T00:00:00Z");
        }
        if (!fromDt.isBefore(toDt)) {
            return badRequest("'from' must be before 'to'");
        }

        List<PositionRow> positions = loadPositions(deviceId, fromDt, toDt);
        if (positions.isEmpty()) {
            return Response.ok(new ArrayList<>()).build();
        }

        List<Map<String, Object>> trips = detectTrips(positions, deviceId);
        LOGGER.info("Detected {} trips", trips.size());

        return Response.ok(trips).build();
    }




    @GET
    @Path("stops")
    public Response getArchiveStops(
            @QueryParam("deviceId") Integer deviceId,
            @QueryParam("from") String from,
            @QueryParam("to") String to) throws StorageException {

        LOGGER.info("=== ARCHIVE STOPS API CALLED === deviceId={} from={} to={}", deviceId, from, to);
        permissionsService.checkAdmin(getUserId());

        if (deviceId == null) {
            return badRequest("'deviceId' query parameter is required");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return badRequest("'from' and 'to' query parameters are required");
        }

        DateTimeFormatter isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        LocalDateTime fromDt, toDt;
        try {
            fromDt = LocalDateTime.parse(from, isoFormatter);
            toDt   = LocalDateTime.parse(to,   isoFormatter);
        } catch (Exception e) {
            return badRequest("Invalid date format. Use: 2025-01-01T00:00:00Z");
        }
        if (!fromDt.isBefore(toDt)) {
            return badRequest("'from' must be before 'to'");
        }

        List<PositionRow> positions = loadPositions(deviceId, fromDt, toDt);
        if (positions.isEmpty()) {
            return Response.ok(new ArrayList<>()).build();
        }

        List<Map<String, Object>> stops = detectStops(positions, deviceId);
        LOGGER.info("Detected {} stops", stops.size());

        return Response.ok(stops).build();
    }


    private List<Map<String, Object>> detectTrips(List<PositionRow> positions, int deviceId) {
        List<Map<String, Object>> trips = new ArrayList<>();

        if (positions.size() < 2) {
            return trips;
        }

        int    tripStart          = -1;
        double accumulatedDistance = 0.0;
        double maxSpeed            = 0.0;
        double totalSpeed          = 0.0;
        int    speedCount          = 0;

        for (int i = 0; i < positions.size(); i++) {
            PositionRow curr = positions.get(i);
            boolean moving = curr.speed >= MIN_SPEED_KNOTS;

            if (tripStart == -1 && moving) {
                tripStart = i;
                accumulatedDistance = 0.0;
                maxSpeed   = curr.speed;
                totalSpeed = curr.speed;
                speedCount = 1;
                continue;
            }

            if (tripStart != -1) {
                PositionRow prev = positions.get(i - 1);
                long gapSeconds = java.time.Duration.between(prev.fixTime, curr.fixTime).getSeconds();

                double segmentDist = haversineMetres(
                        prev.latitude, prev.longitude,
                        curr.latitude, curr.longitude);
                accumulatedDistance += segmentDist;

                if (curr.speed > maxSpeed) {
                    maxSpeed = curr.speed;
                }
                totalSpeed += curr.speed;
                speedCount++;

                boolean gapTooLong = gapSeconds > MAX_GAP_SECONDS;
                boolean lastPoint  = (i == positions.size() - 1);
                boolean stopped    = !moving && gapTooLong;

                if (stopped || lastPoint) {
                    int tripEnd = (lastPoint && moving) ? i : i - 1;

                    PositionRow startPos = positions.get(tripStart);
                    PositionRow endPos   = positions.get(tripEnd);

                    long durationMs  = java.time.Duration.between(startPos.fixTime, endPos.fixTime).toMillis();
                    long durationSec = durationMs / 1000;

                    if (durationSec >= MIN_TRIP_DURATION_SECONDS
                            && accumulatedDistance >= MIN_TRIP_DISTANCE_METRES) {

                        double avgSpeedKmh = speedCount > 0 ? (totalSpeed / speedCount) * 1.852 : 0.0;
                        double maxSpeedKmh = maxSpeed * 1.852;

                        trips.add(buildTripItem(deviceId, startPos, endPos,
                                accumulatedDistance, avgSpeedKmh, maxSpeedKmh, durationMs));
                    }

                    tripStart = -1;
                    accumulatedDistance = 0.0;

                    if (stopped && moving) {
                        tripStart  = i;
                        maxSpeed   = curr.speed;
                        totalSpeed = curr.speed;
                        speedCount = 1;
                    }
                }
            }
        }

        return trips;
    }


    private List<Map<String, Object>> detectStops(List<PositionRow> positions, int deviceId) {
        List<Map<String, Object>> stops = new ArrayList<>();

        if (positions.size() < 2) {
            return stops;
        }
        boolean hasIgnitionData = positions.stream().anyMatch(p -> p.ignition != null);
        int stopStart = -1;

        for (int i = 0; i < positions.size(); i++) {
            PositionRow curr = positions.get(i);
            boolean stopped = curr.speed < MIN_SPEED_KNOTS;

            if (stopStart == -1 && stopped) {
                stopStart = i;
                continue;
            }

            if (stopStart != -1) {
                boolean moving    = curr.speed >= MIN_SPEED_KNOTS;
                boolean lastPoint = (i == positions.size() - 1);

                if (moving || lastPoint) {
                    int stopEnd = moving ? i - 1 : i;

                    PositionRow firstStopPos = positions.get(stopStart);
                    PositionRow lastStopPos  = positions.get(stopEnd);

                    long durationMs  = java.time.Duration
                            .between(firstStopPos.fixTime, lastStopPos.fixTime)
                            .toMillis();
                    long durationSec = durationMs / 1000;

                    if (durationSec >= MIN_STOP_DURATION_SECONDS) {
                        long engineHoursMs = 0L;
                        if (hasIgnitionData) {
                            for (int j = stopStart; j < stopEnd; j++) {
                                PositionRow a = positions.get(j);
                                PositionRow b = positions.get(j + 1);
                                if (Boolean.TRUE.equals(a.ignition)) {
                                    engineHoursMs += java.time.Duration
                                            .between(a.fixTime, b.fixTime)
                                            .toMillis();
                                }
                            }
                        } else {
                            engineHoursMs = durationMs;
                        }

                        stops.add(buildStopItem(
                                deviceId,
                                firstStopPos,
                                lastStopPos,
                                durationMs,
                                engineHoursMs));
                    }

                    stopStart = -1;

                    if (!moving) {
                        stopStart = i;
                    }
                }
            }
        }

        return stops;
    }


    private Map<String, Object> buildTripItem(
            int deviceId,
            PositionRow startPos, PositionRow endPos,
            double distanceMetres,
            double averageSpeedKmh, double maxSpeedKmh,
            long durationMs) {

        DateTimeFormatter isoOut = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'+00:00'");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceId",        deviceId);
        item.put("deviceName",      null);
        item.put("distance",        Math.round(distanceMetres * 10.0) / 10.0);
        item.put("averageSpeed",    averageSpeedKmh);
        item.put("maxSpeed",        maxSpeedKmh);
        item.put("spentFuel",       0.0);
        item.put("startOdometer",   startPos.odometer > 0 ? startPos.odometer : null);
        item.put("endOdometer",     endPos.odometer   > 0 ? endPos.odometer   : null);
        item.put("startTime",       startPos.fixTime.format(isoOut));
        item.put("endTime",         endPos.fixTime.format(isoOut));
        item.put("startPositionId", startPos.id);
        item.put("endPositionId",   endPos.id);
        item.put("startLat",        startPos.latitude);
        item.put("startLon",        startPos.longitude);
        item.put("endLat",          endPos.latitude);
        item.put("endLon",          endPos.longitude);
        item.put("startAddress",    null);
        item.put("endAddress",      null);
        item.put("duration",        durationMs);
        item.put("driverUniqueId",  null);
        item.put("driverName",      null);
        return item;
    }

    private Map<String, Object> buildStopItem(
            int deviceId,
            PositionRow firstStopPos,
            PositionRow lastStopPos,
            long durationMs,
            long engineHoursMs) {

        DateTimeFormatter isoOut = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'+00:00'");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceId",       deviceId);
        item.put("deviceName",     null);
        item.put("distance",       0.0);
        item.put("averageSpeed",   0.0);
        item.put("maxSpeed",       0.0);
        item.put("spentFuel",      0.0);
        item.put("startOdometer",  firstStopPos.odometer > 0 ? firstStopPos.odometer : null);
        item.put("endOdometer",    lastStopPos.odometer  > 0 ? lastStopPos.odometer  : null);
        item.put("startTime",      firstStopPos.fixTime.format(isoOut));
        item.put("endTime",        lastStopPos.fixTime.format(isoOut));
        item.put("positionId",     firstStopPos.id);       // first position of the stop
        item.put("latitude",       firstStopPos.latitude);
        item.put("longitude",      firstStopPos.longitude);
        item.put("address",        null);
        item.put("duration",       durationMs);
        item.put("engineHours",    engineHoursMs);
        return item;
    }


    private static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }


    private File downloadFromSpaces(String bucket, String key) throws Exception {
        String s3Url = "s3://" + bucket + "/" + key;
        String tmpFileName = "archive_" + UUID.randomUUID() + ".parquet";
        File tmpFile = new File(System.getProperty("java.io.tmpdir"), tmpFileName);

        List<String> cmd = buildS3CmdBase();
        cmd.add("get");
        cmd.add("--force");
        cmd.add(s3Url);
        cmd.add(tmpFile.getAbsolutePath());

        LOGGER.info("Downloading: {} → {}", s3Url, tmpFile.getAbsolutePath());
        runProcess(cmd);
        return tmpFile;
    }

    private void deleteTempFile(File file) {
        if (file != null && file.exists()) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                LOGGER.warn("Could not delete temp file: {}", file.getAbsolutePath());
            }
        }
    }


    private static double extractDoubleFromJson(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return 0.0;
        }
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) {
            return 0.0;
        }
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.'
                || json.charAt(end) == '-')) {
            end++;
        }
        if (start == end) {
            return 0.0;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static Boolean extractBooleanFromJson(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) {
            return null;
        }
        String rest = json.substring(colon + 1).trim();
        if (rest.startsWith("true"))  {
            return Boolean.TRUE;
        }
        if (rest.startsWith("false")) {
            return Boolean.FALSE;
        }
        return null;
    }


    private static long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return Long.parseLong(o.toString().trim());
    }

    private static int toInt(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        return Integer.parseInt(o.toString().trim());
    }

    private static double toDouble(Object o) {
        if (o == null) {
            return 0.0;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        return Double.parseDouble(o.toString().trim());
    }

    private static LocalDateTime parseDateTime(Object o, DateTimeFormatter fmt) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, fmt);
        } catch (Exception e) {
            return null;
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}")
                .build();
    }


    private static final class PositionRow {
        private long          id;
        private int           deviceId;
        private LocalDateTime fixTime;
        private double        latitude;
        private double        longitude;
        private double        speed;
        private double        course;
        private double        altitude;
        private double        odometer;
        private Boolean       ignition;

        public long getId() {
            return id;
        }
        public int getDeviceId() {
            return deviceId;
        }
        public LocalDateTime getFixTime() {
            return fixTime;
        }
        public double getLatitude() {
            return latitude;
        }
        public double getLongitude() {
            return longitude;
        }
        public double getSpeed() {
            return speed;
        }
        public double getCourse() {
            return course;
        }
        public double getAltitude() {
            return altitude;
        }
        public double getOdometer() {
            return odometer;
        }
        public Boolean getIgnition() {
            return ignition;
        }

    }



    private List<PositionRow> loadPositions(
            int deviceId, LocalDateTime fromDt, LocalDateTime toDt) {

        String bucket = requireBucket();
        List<Map<String, Object>> allPositions = new ArrayList<>();
        DateTimeFormatter dbFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        LocalDateTime cursor = fromDt.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        while (!cursor.isAfter(toDt)) {
            String label = String.format("%04d-%02d", cursor.getYear(), cursor.getMonthValue());
            String key   = "archive/positions/" + deviceId + "/" + label + ".parquet";
            LOGGER.info("Trying to fetch Parquet: {}", key);

            File tmpFile = null;
            try {
                tmpFile = downloadFromSpaces(bucket, key);
                List<Map<String, Object>> rows = readParquetFile(tmpFile);
                LOGGER.info("Loaded {} positions from {}", rows.size(), key);
                allPositions.addAll(rows);
            } catch (Exception e) {
                LOGGER.warn("Could not load {}: {}", key, e.getMessage());
            } finally {
                deleteTempFile(tmpFile);
            }
            cursor = cursor.plusMonths(1);
        }

        LOGGER.info("Total raw positions loaded: {}", allPositions.size());

        List<PositionRow> positions = new ArrayList<>();
        for (Map<String, Object> row : allPositions) {
            try {
                PositionRow p = new PositionRow();
                p.id        = toLong(row.get("id"));
                p.deviceId  = toInt(row.get("deviceid"));
                p.fixTime   = parseDateTime(row.get("fixtime"), dbFormatter);
                p.latitude  = toDouble(row.get("latitude"));
                p.longitude = toDouble(row.get("longitude"));
                p.speed     = toDouble(row.get("speed"));
                p.course    = toDouble(row.get("course"));
                p.altitude  = toDouble(row.get("altitude"));

                String attrs = row.get("attributes") != null ? row.get("attributes").toString() : null;
                if (attrs != null && !attrs.isBlank() && attrs.startsWith("{")) {
                    p.odometer = extractDoubleFromJson(attrs, "odometer");
                    if (p.odometer == 0.0) {
                        p.odometer = extractDoubleFromJson(attrs, "totalDistance");
                    }
                    p.ignition = extractBooleanFromJson(attrs, "ignition");
                }

                if (p.deviceId != deviceId) {
                    continue;
                }
                if (p.fixTime == null) {
                    continue;
                }
                if (p.fixTime.isBefore(fromDt) || p.fixTime.isAfter(toDt)) {
                    continue;
                }

                positions.add(p);
            } catch (Exception e) {
                LOGGER.warn("Skipping bad position row: {}", e.getMessage());
            }
        }

        positions.sort(Comparator.comparing(p -> p.fixTime));
        LOGGER.info("Positions after filter & sort: {}", positions.size());
        return positions;
    }




}

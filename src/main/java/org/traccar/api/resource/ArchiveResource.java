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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for cold-storage archive retrieval.
 *
 * All endpoints are admin-only.
 *
 *   GET /api/archive/list?prefix=archive/positions/42/
 *       Returns JSON array of { key, size, lastModified } for every Parquet
 *       file stored under the given prefix in DigitalOcean Spaces.
 *
 *   GET /api/archive/records?key=archive/positions/42/2024-07.parquet
 *       Downloads the specified Parquet file from Spaces and returns its
 *       rows as a JSON array of field-map objects.
 */
@Path("archive")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ArchiveResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveResource.class);

    @Inject
    private Config config;


    private List<String> buildS3CmdBase() {

        List<String> cmd = new ArrayList<>();

        cmd.add("C:\\Python311\\python.exe");
        cmd.add("C:\\Python311\\Scripts\\s3cmd");

        // DO NOT pass --config
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
        System.setProperty("hadoop.home.dir", "C:/");

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

}

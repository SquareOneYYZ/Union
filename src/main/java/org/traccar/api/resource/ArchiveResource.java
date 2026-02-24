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
        cmd.add("s3cmd");
        String cfgFile = config.getString(Keys.ARCHIVE_S3CMD_CONFIG_FILE);
        if (cfgFile != null && !cfgFile.isBlank()) {
            String expanded = cfgFile.startsWith("~")
                    ? System.getProperty("user.home") + cfgFile.substring(1)
                    : cfgFile;
            cmd.add("--config=" + expanded);
        }
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
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        StringBuilder stderr = new StringBuilder();
        try (BufferedReader errReader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream()))) {
            String line;
            while ((line = errReader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity("{\"error\":\"s3cmd failed: " + stderr.toString().replace("\"", "'") + "\"}")
                            .build());
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
    public Response getArchiveRecords(@QueryParam("key") String key) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        if (key == null || key.isBlank()) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":\"'key' query parameter is required\"}")
                            .build());
        }

        String bucket = requireBucket();
        String s3Url = "s3://" + bucket + "/" + key;

        String tmpFileName = "archive_" + UUID.randomUUID() + ".parquet";
        File tmpFile = new File(System.getProperty("java.io.tmpdir"), tmpFileName);

        try {
            List<String> cmd = buildS3CmdBase();
            cmd.add("get");
            cmd.add("--force");
            cmd.add(s3Url);
            cmd.add(tmpFile.getAbsolutePath());

            try {
                runProcess(cmd);
            } catch (WebApplicationException wae) {
                throw wae;
            } catch (Exception e) {
                LOGGER.error("s3cmd get failed for key: {}", key, e);
                throw new WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                .entity("{\"error\":\"" + e.getMessage() + "\"}")
                                .build());
            }

            List<Map<String, Object>> records = readParquetFile(tmpFile);
            return Response.ok(records).build();

        } finally {
            if (tmpFile.exists()) {
                try {
                    Files.delete(tmpFile.toPath());
                } catch (IOException e) {
                    LOGGER.warn("Could not delete temp file: {}", tmpFile.getAbsolutePath());
                }
            }
        }
    }


    private List<Map<String, Object>> readParquetFile(File file) {
        List<Map<String, Object>> records = new ArrayList<>();

        Configuration hadoopConf = new Configuration();
        hadoopConf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
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
            LOGGER.error("Failed to read Parquet file: {}", file.getAbsolutePath(), e);
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity("{\"error\":\"Failed to read Parquet file: " + e.getMessage() + "\"}")
                            .build());
        }

        return records;
    }

}

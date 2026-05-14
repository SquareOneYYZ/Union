package org.traccar.storage.spaces;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


@Singleton
public class DigitalOceanSpacesService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DigitalOceanSpacesService.class);

    private final String pythonExe;
    private final String s3cmdScript;
    private final String s3cmdConfigFile;
    private final String bucket;
    private final String tempDir;
    private final boolean available;

    @Inject
    public DigitalOceanSpacesService(Config config) {
        this.pythonExe      = config.getString(Keys.ARCHIVE_PYTHON_EXE);
        this.s3cmdScript    = config.getString(Keys.ARCHIVE_S3CMD_SCRIPT);
        this.s3cmdConfigFile = config.getString(Keys.ARCHIVE_S3CMD_CONFIG_FILE);
        this.bucket         = config.getString(Keys.ARCHIVE_SPACES_BUCKET);
        this.tempDir        = config.getString(Keys.ARCHIVE_TEMP_DIR);

        boolean ready = pythonExe != null && !pythonExe.isBlank()
                && s3cmdScript != null && !s3cmdScript.isBlank()
                && s3cmdConfigFile != null && !s3cmdConfigFile.isBlank()
                && bucket != null && !bucket.isBlank()
                && tempDir != null && !tempDir.isBlank();

        if (!ready) {
            LOGGER.warn("DigitalOcean Spaces (s3cmd) not fully configured — uploads will be skipped");
        }
        this.available = ready;
    }

    public boolean isAvailable() {
        return available;
    }


    public void put(String folder, long id, String json) {
        if (!available) {
            return;
        }

        Path tempFile = null;
        try {
            File tempDir = new File(this.tempDir);
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            tempFile = Files.createTempFile(tempDir.toPath(), "geofence-" + id + "-", ".json");
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);

            String s3Destination = "s3://" + bucket + "/" + folder + "/" + id + "/data.json";

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExe,
                    s3cmdScript,
                    "put",
                    tempFile.toAbsolutePath().toString(),
                    s3Destination,
                    "--config=" + s3cmdConfigFile,
                    "--acl-public"
            );
            pb.redirectErrorStream(true);

            LOGGER.debug("Uploading geofence {} to Spaces: {}", id, s3Destination);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                LOGGER.debug("Uploaded geofence {} to Spaces successfully", id);
            } else {
                LOGGER.warn("s3cmd upload failed for geofence {} (exit {}): {}", id, exitCode, output.trim());
            }

        } catch (IOException | InterruptedException e) {
            LOGGER.warn("Failed to upload geofence {} to DigitalOcean Spaces", id, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete temp file {}", tempFile, e);
                }
            }
        }
    }

}

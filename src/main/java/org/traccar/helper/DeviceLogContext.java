package org.traccar.helper;

import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.session.cache.CacheManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class DeviceLogContext {

    private DeviceLogContext() {
    }

    private static final ThreadLocal<Long> DEVICE_ID = new ThreadLocal<>();
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("deviceId\\s*[=:]\\s*(\\d+)");
    private static final Pattern DEVICE_PATTERN = Pattern.compile("Device\\s+(\\d+)");

    private static volatile Config config;
    private static volatile CacheManager cacheManager;

    public static void initialize(Config config, CacheManager cacheManager) {
        DeviceLogContext.config = config;
        DeviceLogContext.cacheManager = cacheManager;
    }

    public static void setDeviceId(long deviceId) {
        if (deviceId > 0) {
            DEVICE_ID.set(deviceId);
        }
    }

    public static void clear() {
        DEVICE_ID.remove();
    }

    public static boolean isLoggable(LogRecord record) {
        if (record == null || record.getLoggerName() == null) {
            return false;
        }
        if (record.getLoggerName().startsWith("sun")) {
            return false;
        }

        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
            return true;
        }

        String loggerName = record.getLoggerName();
        if (!loggerName.startsWith("org.traccar")) {
            return record.getLevel().intValue() >= Level.INFO.intValue();
        }

        Long deviceId = DEVICE_ID.get();
        if (deviceId == null) {
            deviceId = extractDeviceId(record.getMessage());
        }
        if (deviceId == null) {
            return true;
        }

        Config localConfig = config;
        CacheManager localCacheManager = cacheManager;
        if (localConfig == null || localCacheManager == null) {
            return true;
        }

        if (localConfig.getBoolean(Keys.DEVICE_DEBUG_LOGGING)) {
            return true;
        }

        Device device = localCacheManager.getObject(Device.class, deviceId);
        if (device != null && device.getDebugLogging()) {
            return true;
        }

        return false;
    }

    private static Long extractDeviceId(String message) {
        if (message == null) {
            return null;
        }
        Matcher deviceIdMatcher = DEVICE_ID_PATTERN.matcher(message);
        if (deviceIdMatcher.find()) {
            return Long.parseLong(deviceIdMatcher.group(1));
        }
        Matcher deviceMatcher = DEVICE_PATTERN.matcher(message);
        if (deviceMatcher.find()) {
            return Long.parseLong(deviceMatcher.group(1));
        }
        return null;
    }
}

package org.traccar.helper;

import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.session.cache.CacheManager;

import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class DeviceLogContext {

    private DeviceLogContext() {
    }

    private static final ThreadLocal<Long> DEVICE_ID = new ThreadLocal<>();

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

        if (!record.getLoggerName().startsWith("org.traccar")) {
            Level configuredLevel = Logger.getLogger("").getLevel();
            if (configuredLevel == null) {
                configuredLevel = Level.INFO;
            }
            return record.getLevel().intValue() >= configuredLevel.intValue();
        }


        Long deviceId = DEVICE_ID.get();
        if (deviceId == null) {
            Level configuredLevel = Logger.getLogger("").getLevel();
            if (configuredLevel == null) {
                configuredLevel = Level.INFO;
            }
            return record.getLevel().intValue() >= configuredLevel.intValue();
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
        if (device == null) {
            return true;
        }

        return device.getDebugLogging();
    }


}

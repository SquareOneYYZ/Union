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
    private record Ctx(long deviceId, boolean loggable) { }

    private static final ThreadLocal<Ctx> CONTEXT = new ThreadLocal<>();

    private static volatile Config config;
    private static volatile CacheManager cacheManager;

    public static void initialize(Config config, CacheManager cacheManager) {
        DeviceLogContext.config = config;
        DeviceLogContext.cacheManager = cacheManager;
    }

    public static void setDeviceId(long deviceId) {
        if (deviceId > 0) {
            CONTEXT.set(new Ctx(deviceId, resolveLoggable(deviceId)));
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }

    private static boolean resolveLoggable(long deviceId) {
        Config localConfig = config;
        CacheManager localCacheManager = cacheManager;
        if (localConfig == null || localCacheManager == null) {
            return true;
        }
        if (localConfig.getBoolean(Keys.DEVICE_DEBUG_LOGGING)) {
            return true;
        }
        Device device = localCacheManager.getObject(Device.class, deviceId);
        return device == null || device.getDebugLogging();
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

        Ctx ctx = CONTEXT.get();
        if (ctx == null) {
            Level configuredLevel = Logger.getLogger("").getLevel();
            if (configuredLevel == null) {
                configuredLevel = Level.INFO;
            }
            return record.getLevel().intValue() >= configuredLevel.intValue();
        }

        return ctx.loggable();
    }

}

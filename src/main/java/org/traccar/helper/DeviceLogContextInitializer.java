package org.traccar.helper;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.session.cache.CacheManager;

@Singleton
public class DeviceLogContextInitializer {

    @Inject
    public DeviceLogContextInitializer(Config config, CacheManager cacheManager) {
        DeviceLogContext.initialize(config, cacheManager);
    }
}

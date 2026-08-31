/*
 * Copyright 2012 - 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.geocoder.Geocoder;
import org.traccar.model.Position;

import java.util.concurrent.atomic.AtomicLong;
import org.traccar.session.cache.CacheManager;

public class GeocoderHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeocoderHandler.class);

    /** Completed geocodes between service-time summaries. */
    private static final int SERVICE_TIME_SAMPLE_INTERVAL = 500;

    private final AtomicLong geocodeCount = new AtomicLong();
    private final AtomicLong geocodeNanosTotal = new AtomicLong();
    private final AtomicLong geocodeNanosMax = new AtomicLong();
    private final AtomicLong geocodeFailures = new AtomicLong();

    private final Geocoder geocoder;
    private final CacheManager cacheManager;
    private final boolean ignorePositions;
    private final boolean processInvalidPositions;
    private final int reuseDistance;

    public GeocoderHandler(Config config, Geocoder geocoder, CacheManager cacheManager) {
        this.geocoder = geocoder;
        this.cacheManager = cacheManager;
        ignorePositions = config.getBoolean(Keys.GEOCODER_IGNORE_POSITIONS);
        processInvalidPositions = config.getBoolean(Keys.GEOCODER_PROCESS_INVALID_POSITIONS);
        reuseDistance = config.getInteger(Keys.GEOCODER_REUSE_DISTANCE, 0);
    }

    /**
     * Records one completed geocode and emits a summary every
     * {@link #SERVICE_TIME_SAMPLE_INTERVAL} completions.
     *
     * <p>This handler runs inside the position chain, so its worker pool is sized by Little's Law
     * the same way the enrichment one is - and service time is the input that arithmetic cannot
     * derive from the fleet. {@code geocoder.client.maxConcurrent} defaults to 256, which covers
     * ~1.35 s at the measured peak; this is what says whether that is right.
     */
    private void recordServiceTime(long startNanos, boolean failed) {
        // Instrumentation must never be able to break the thing it measures. This runs
        // inside an async provider callback, BEFORE callback.processed(...) - and
        // JsonGeocoder.completed() does not wrap its callback, so an exception escaping
        // here would leave that device's handler chain never completing, its queue
        // filling, and its positions dropped. A lost metric sample is the correct
        // failure; a stalled device is not.
        try {
            long elapsed = System.nanoTime() - startNanos;
            long count = geocodeCount.incrementAndGet();
            geocodeNanosTotal.addAndGet(elapsed);
            geocodeNanosMax.accumulateAndGet(elapsed, Math::max);
            if (failed) {
                geocodeFailures.incrementAndGet();
            }
            if (count % SERVICE_TIME_SAMPLE_INTERVAL == 0) {
                LOGGER.info("Geocoder service time over {} calls: mean {} ms, max {} ms since last "
                                + "summary, {} failures - see geocoder.client.maxConcurrent",
                        count, geocodeNanosTotal.get() / count / 1_000_000L,
                        geocodeNanosMax.getAndSet(0) / 1_000_000L, geocodeFailures.get());
            }

        } catch (RuntimeException e) {
            LOGGER.debug("Service-time sampling failed", e);
        }
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!ignorePositions && (processInvalidPositions || position.getValid())) {
            if (reuseDistance != 0) {
                Position lastPosition = cacheManager.getPosition(position.getDeviceId());
                if (lastPosition != null && lastPosition.getAddress() != null
                        && position.getDouble(Position.KEY_DISTANCE) <= reuseDistance) {
                    position.setAddress(lastPosition.getAddress());
                    callback.processed(false);
                    return;
                }
            }

            long startNanos = System.nanoTime();
            geocoder.getAddress(position.getLatitude(), position.getLongitude(),
                    new Geocoder.ReverseGeocoderCallback() {
                @Override
                public void onSuccess(String address) {
                    recordServiceTime(startNanos, false);
                    position.setAddress(address);
                    callback.processed(false);
                }

                @Override
                public void onFailure(Throwable e) {
                    recordServiceTime(startNanos, true);
                    LOGGER.warn("Geocoding failed", e);
                    callback.processed(false);
                }
            });
        } else {
            callback.processed(false);
        }
    }

}

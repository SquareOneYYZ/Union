/*
 * Copyright 2016 - 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Calendar;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.GeofenceDistanceState;
import org.traccar.storage.Storage;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeofenceEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceEventHandler.class);

    private final CacheManager cacheManager;
    private final RedisCache redis;
    private final Storage storage;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    @Inject
    public GeofenceEventHandler(CacheManager cacheManager, RedisCache redis, Storage storage) {
        this.cacheManager = cacheManager;
        this.redis = redis;
        this.storage = storage;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!PositionUtil.isLatest(cacheManager, position)) {
            return;
        }

        long deviceId = position.getDeviceId();
        List<Long> currentGeofences = position.getGeofenceIds();

        // Handle geofence events
        List<Long> oldGeofences = new ArrayList<>();
        Position lastPosition = cacheManager.getPosition(deviceId);
        if (lastPosition != null && lastPosition.getGeofenceIds() != null) {
            oldGeofences.addAll(lastPosition.getGeofenceIds());
        }

        List<Long> newGeofences = new ArrayList<>();
        if (currentGeofences != null) {
            newGeofences.addAll(currentGeofences);
            newGeofences.removeAll(oldGeofences);
            oldGeofences.removeAll(currentGeofences);
        }

        for (long geofenceId : oldGeofences) {
            Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
            if (geofence != null) {
                long calendarId = geofence.getCalendarId();
                Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
                if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                    Event event = new Event(Event.TYPE_GEOFENCE_EXIT, position);
                    event.setGeofenceId(geofenceId);
                    event.set(Position.KEY_TOTAL_DISTANCE, position.getDouble(Position.KEY_TOTAL_DISTANCE));
                    callback.eventDetected(event);
                }
            }
        }
        for (long geofenceId : newGeofences) {
            Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
            if (geofence == null) {
                continue;
            }
            long calendarId = geofence.getCalendarId();
            Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
            if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                Event event = new Event(Event.TYPE_GEOFENCE_ENTER, position);
                event.setGeofenceId(geofenceId);
                event.set(Position.KEY_TOTAL_DISTANCE, position.getDouble(Position.KEY_TOTAL_DISTANCE));
                callback.eventDetected(event);
            }
        }

        // Handle geofence distance tracking
        String cacheKey = "geo:dev:" + deviceId + ":gf";
        GeofenceDistanceState state = null;

        try {
            if (redis.isAvailable() && redis.exists(cacheKey)) {
                String json = redis.get(cacheKey);
                LOGGER.debug("Redis hit for geofencedistance deviceId={}", deviceId);
                state = objectMapper.readValue(json, GeofenceDistanceState.class);
            } else if (!redis.isAvailable() && localCache.containsKey(cacheKey)) {
                String json = localCache.get(cacheKey);
                LOGGER.debug("Local cache hit for geofencedistance deviceId={}", deviceId);
                state = objectMapper.readValue(json, GeofenceDistanceState.class);
            }
        } catch (Exception e) {
            LOGGER.warn("Error reading GeofenceDistanceState from cache for deviceId={}", deviceId, e);
        }

        if (state == null) {
            state = new GeofenceDistanceState(deviceId);
        }

        if (currentGeofences == null || currentGeofences.isEmpty()) {
            state.handleExitAll(position);
        } else {
            state.updateState(position, currentGeofences);
        }

        try {
            String updatedJson = objectMapper.writeValueAsString(state);
            if (redis.isAvailable()) {
                redis.set(cacheKey, updatedJson);
            } else {
                localCache.put(cacheKey, updatedJson);
            }
        } catch (Exception e) {
            LOGGER.warn("Error writing GeofenceDistanceState to cache for deviceId={}", deviceId, e);
        }

        List<DeviceGeofenceDistance> records = state.getRecords();
        if (records != null && !records.isEmpty()) {
            for (DeviceGeofenceDistance record : records) {
                try {
                    record.setId(storage.addObject(record, new Request(new Columns.Exclude("id"))));
                    LOGGER.debug("Saved geofence distance: {}", record.getId());
                } catch (Exception e) {
                    LOGGER.error("DB save error", e);
                }
            }
            state.clearRecords();
        }
    }
}

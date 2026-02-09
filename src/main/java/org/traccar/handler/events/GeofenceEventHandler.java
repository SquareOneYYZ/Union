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

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Calendar;
import org.traccar.model.DeviceGeofenceSegment;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.List;

public class GeofenceEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceEventHandler.class);

    private final CacheManager cacheManager;
    private final Storage storage;

    @Inject
    public GeofenceEventHandler(CacheManager cacheManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.storage = storage;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!PositionUtil.isLatest(cacheManager, position)) {
            return;
        }

        long deviceId = position.getDeviceId();
        List<Long> currentGeofences = position.getGeofenceIds();

        List<Long> oldGeofences = new ArrayList<>();
        Position lastPosition = cacheManager.getPosition(deviceId);
        if (lastPosition != null && lastPosition.getGeofenceIds() != null) {
            oldGeofences.addAll(lastPosition.getGeofenceIds());
        }

        List<Long> enteredGeofences = new ArrayList<>();
        List<Long> exitedGeofences = new ArrayList<>(oldGeofences);

        if (currentGeofences != null) {
            enteredGeofences.addAll(currentGeofences);
            enteredGeofences.removeAll(oldGeofences);
            exitedGeofences.removeAll(currentGeofences);
        }

        double totalDistance = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        // Handle EXIT events - close inside segment, create outside segment
        for (long geofenceId : exitedGeofences) {
            Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
            if (geofence != null) {
                long calendarId = geofence.getCalendarId();
                Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
                if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                    Event event = new Event(Event.TYPE_GEOFENCE_EXIT, position);
                    event.setGeofenceId(geofenceId);
                    event.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
                    callback.eventDetected(event);

                    // Close the "inside" segment and create "outside" segment
                    closeSegment(deviceId, geofenceId, "inside", position, totalDistance);
                    createSegment(deviceId, geofenceId, "outside", position, totalDistance);
                }
            }
        }

        // Handle ENTER events - close outside segment, create inside segment
        for (long geofenceId : enteredGeofences) {
            Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
            if (geofence == null) {
                continue;
            }
            long calendarId = geofence.getCalendarId();
            Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
            if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                Event event = new Event(Event.TYPE_GEOFENCE_ENTER, position);
                event.setGeofenceId(geofenceId);
                event.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
                callback.eventDetected(event);

                // Close any "outside" segment and create new "inside" segment
                closeSegment(deviceId, geofenceId, "outside", position, totalDistance);
                createSegment(deviceId, geofenceId, "inside", position, totalDistance);
            }
        }
    }

    private void createSegment(long deviceId, long geofenceId, String type,
                               Position position, double totalDistance) {
        try {
            DeviceGeofenceSegment segment = new DeviceGeofenceSegment();
            segment.setDeviceId(deviceId);
            segment.setGeofenceId(geofenceId);
            segment.setType(type);
            segment.setEnterPositionId(position.getId());
            segment.setEnterTime(position.getDeviceTime());
            segment.setOdoStart(totalDistance);
            segment.setOpen(true);

            segment.setId(storage.addObject(segment, new Request(
                    new Columns.Include("deviceId", "geofenceId", "type", "enterPositionId", "enterTime",
                            "odoStart", "open"))));
            LOGGER.debug("Created {} geofence segment: deviceId={}, geofenceId={}, segmentId={}",
                    type, deviceId, geofenceId, segment.getId());
        } catch (StorageException e) {
            LOGGER.error("Failed to create {} geofence segment for deviceId={}, geofenceId={}",
                    type, deviceId, geofenceId, e);
        }
    }

    private void closeSegment(long deviceId, long geofenceId, String type,
                              Position position, double totalDistance) {
        try {
            // Find the open segment for this device, geofence, and type
            var conditions = new ArrayList<Condition>();
            conditions.add(new Condition.Equals("deviceId", deviceId));
            conditions.add(new Condition.Equals("geofenceId", geofenceId));
            conditions.add(new Condition.Equals("type", type));
            conditions.add(new Condition.Equals("open", true));

            var segments = storage.getObjects(DeviceGeofenceSegment.class,
                    new Request(new Columns.All(), Condition.merge(conditions)));

            if (segments.isEmpty()) {
                // No open segment to close - this is normal for first enter/exit
                LOGGER.debug("No open {} segment found to close for deviceId={}, geofenceId={}",
                        type, deviceId, geofenceId);
                return;
            }

            // Close the open segment
            DeviceGeofenceSegment segment = segments.iterator().next();
            segment.setExitPositionId(position.getId());
            segment.setExitTime(position.getDeviceTime());
            segment.setOdoEnd(totalDistance);
            segment.setDistance(totalDistance - segment.getOdoStart());
            segment.setOpen(false);

            storage.updateObject(segment, new Request(
                    new Columns.Include("exitPositionId", "exitTime", "odoEnd", "distance", "open"),
                    new Condition.Equals("id", segment.getId())));

            LOGGER.debug("Closed {} geofence segment: deviceId={}, geofenceId={}, segmentId={}, distance={}",
                    type, deviceId, geofenceId, segment.getId(), segment.getDistance());
        } catch (StorageException e) {
            LOGGER.error("Failed to close {} geofence segment for deviceId={}, geofenceId={}",
                    type, deviceId, geofenceId, e);
        }
    }
}

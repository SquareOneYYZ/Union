/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.reports;

import org.apache.poi.ss.util.WorkbookUtil;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceSegment;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.DeviceReportSection;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;

public class DeviceGeofenceDistanceReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final Storage storage;

    @Inject
    public DeviceGeofenceDistanceReportProvider(
            Config config,
            ReportUtils reportUtils,
            Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.storage = storage;
    }

    public Collection<DeviceGeofenceSegment> getObjects(
            long userId, long deviceId, long geofenceId, Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        var conditions = new LinkedList<Condition>();
        if (deviceId > 0) {
            conditions.add(new Condition.Equals("deviceId", deviceId));
        }
        if (geofenceId > 0) {
            conditions.add(new Condition.Equals("geofenceId", geofenceId));
        }
        if (from != null && to != null) {
            conditions.add(new Condition.Between("enterTime", "from", from, "to", to));
        }

        Collection<DeviceGeofenceSegment> segments = storage.getObjects(
                DeviceGeofenceSegment.class,
                new Request(new Columns.All(), Condition.merge(conditions), new Order("enterTime")));

        segments.removeIf(segment -> {
            try {
                reportUtils.getObject(userId, Device.class, segment.getDeviceId());
                return false;
            } catch (Exception e) {
                return true;
            }
        });

        return segments;
    }

    public void getExcel(
            OutputStream outputStream, long userId, long deviceId, long geofenceId,
            Date from, Date to) throws StorageException, IOException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<DeviceReportSection> devicesDistances = new ArrayList<>();
        ArrayList<String> sheetNames = new ArrayList<>();
        HashMap<Long, String> geofenceNames = new HashMap<>();

        var conditions = new LinkedList<Condition>();
        if (deviceId > 0) {
            conditions.add(new Condition.Equals("deviceId", deviceId));
        }
        if (geofenceId > 0) {
            conditions.add(new Condition.Equals("geofenceId", geofenceId));
        }
        if (from != null && to != null) {
            conditions.add(new Condition.Between("enterTime", "from", from, "to", to));
        }

        Collection<DeviceGeofenceSegment> allSegments = storage.getObjects(
                DeviceGeofenceSegment.class,
                new Request(new Columns.All(), Condition.merge(conditions), new Order("enterTime")));

        HashMap<Long, Collection<DeviceGeofenceSegment>> segmentsByDevice = new HashMap<>();
        for (DeviceGeofenceSegment segment : allSegments) {
            try {
                reportUtils.getObject(userId, Device.class, segment.getDeviceId());
                segmentsByDevice.computeIfAbsent(segment.getDeviceId(), k -> new ArrayList<>()).add(segment);
                if (segment.getGeofenceId() > 0 && !geofenceNames.containsKey(segment.getGeofenceId())) {
                    Geofence geofence = reportUtils.getObject(userId, Geofence.class, segment.getGeofenceId());
                    if (geofence != null) {
                        geofenceNames.put(segment.getGeofenceId(), geofence.getName());
                    }
                }
            } catch (Exception e) {
            }
        }

        for (Long devId : segmentsByDevice.keySet()) {
            Device device = storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", devId)));
            if (device != null) {
                Collection<DeviceGeofenceSegment> deviceSegments = segmentsByDevice.get(devId);

                DeviceReportSection deviceSection = new DeviceReportSection();
                deviceSection.setDeviceName(device.getName());
                sheetNames.add(WorkbookUtil.createSafeSheetName(deviceSection.getDeviceName()));
                if (device.getGroupId() > 0) {
                    Group group = storage.getObject(Group.class, new Request(
                            new Columns.All(), new Condition.Equals("id", device.getGroupId())));
                    if (group != null) {
                        deviceSection.setGroupName(group.getName());
                    }
                }
                deviceSection.setObjects(deviceSegments);
                devicesDistances.add(deviceSection);
            }
        }

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export",
                "devicegeofencedistances.xlsx").toFile();
        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("devices", devicesDistances);
            context.putVar("sheetNames", sheetNames);
            context.putVar("geofenceNames", geofenceNames);
            context.putVar("from", from);
            context.putVar("to", to);
            reportUtils.processTemplateWithSheets(inputStream, outputStream, context);
        }
    }
}

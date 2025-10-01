/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
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

import jakarta.inject.Inject;
import org.jxls.util.JxlsHelper;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Message;
import org.traccar.model.User;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.DeviceReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DevicesReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final Storage storage;

    @Inject
    public DevicesReportProvider(Config config, ReportUtils reportUtils, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.storage = storage;
    }

    public Collection<DeviceReportItem> getObjects(
            long userId,
            List<Long> deviceIds,
            List<Long> groupIds,
            String model,
            String status,
            String name,
            String identifier,
            String vin) throws StorageException {

        var positions = PositionUtil.getLatestPositions(storage, userId).stream()
                .collect(Collectors.toMap(Message::getDeviceId, p -> p));

        var groups = storage.getObjects(Group.class, new Request(
                        new Columns.All(),
                        new Condition.Permission(User.class, userId, Group.class)))
                .stream()
                .collect(Collectors.toMap(Group::getId, g -> g));

//        return storage.getObjects(Device.class, new Request(
//                new Columns.All(),
//                new Condition.Permission(User.class, userId, Device.class))).stream()
        // Base request
        var request = new Request(
                new Columns.All(),
                new Condition.Permission(User.class, userId, Device.class));

        var devices = storage.getObjects(Device.class, request);

        // Apply filters
        Stream<Device> stream = devices.stream();

        if (deviceIds != null && !deviceIds.isEmpty()) {
            stream = stream.filter(d -> deviceIds.contains(d.getId()));
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            stream = stream.filter(d -> groupIds.contains(d.getGroupId()));
        }
        if (model != null && !model.isEmpty()) {
            stream = stream.filter(d -> d.getModel() != null && d.getModel().toLowerCase()
                    .contains(model.toLowerCase()));
        }
        if (status != null && !status.isEmpty()) {
            stream = stream.filter(d -> d.getStatus() != null && d.getStatus().toLowerCase()
                    .contains(status.toLowerCase()));
        }
        if (name != null && !name.isEmpty()) {
            stream = stream.filter(d -> d.getName() != null && d.getName().toLowerCase().contains(name.toLowerCase()));
        }
        if (identifier != null && !identifier.isEmpty()) {
            stream = stream.filter(d -> d.getUniqueId() != null && d.getUniqueId().toLowerCase()
                    .contains(identifier.toLowerCase()));
        }
        if (vin != null && !vin.isEmpty()) {
            stream = stream.filter(d -> d.getVin() != null && d.getVin().toLowerCase().contains(vin.toLowerCase()));
        }

        return stream
                .map(device -> new DeviceReportItem(
                        device, positions.get(device.getId()), groups.get(device.getGroupId())))
                .toList();
    }

    public void getExcel(OutputStream outputStream, long userId,
                         List<Long> deviceIds, List<Long> groupIds,
                         String model, String status, String name,
                         String identifier, String vin) throws StorageException, IOException {

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "devices.xlsx").toFile();
        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("items", getObjects(userId, deviceIds, groupIds, model, status, name, identifier, vin));
            JxlsHelper.getInstance().setUseFastFormulaProcessor(false)
                    .processTemplate(inputStream, outputStream, context);
        }
    }
}

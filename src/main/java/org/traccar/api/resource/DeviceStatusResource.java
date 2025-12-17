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
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("devicestatus")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceStatusResource extends BaseResource {

    @GET
    public Collection<Map<String, Object>> get(
            @QueryParam("type") String type,
            @QueryParam("deviceName") String deviceNameFilter,
            @QueryParam("imeiSerialNumber") String imeiFilter) throws StorageException {
        List<Map<String, Object>> result = new ArrayList<>();
        if ("inactive".equals(type)) {
            Date twentyFourHoursAgo = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000);
            Collection<Device> devices = storage.getObjects(Device.class, new Request(
                    new Columns.All(),
                    new Condition.Permission(User.class, getUserId(), Device.class)));
            for (Device device : devices) {
                if (device.getLastUpdate() != null && device.getLastUpdate().before(twentyFourHoursAgo)) {
                    if (deviceNameFilter != null && !device.getName().toLowerCase().
                            contains(deviceNameFilter.toLowerCase())) {
                        continue;
                    }
                    if (imeiFilter != null && !device.getUniqueId().toLowerCase().
                            contains(imeiFilter.toLowerCase())) {
                        continue;
                    }
                    Map<String, Object> deviceInfo = new HashMap<>();
                    deviceInfo.put("deviceName", device.getName());
                    deviceInfo.put("type", "inactive");
                    deviceInfo.put("imeiSerialNumber", device.getUniqueId());
//                    deviceInfo.put("deviceId", device.getId());
                    deviceInfo.put("lastCommunication", device.getLastUpdate());
                    if (device.getGroupId() > 0) {
                        Group group = storage.getObject(Group.class, new Request(
                                new Columns.All(),
                                new Condition.Equals("id", device.getGroupId())));
                        if (group != null) {
                            deviceInfo.put("assignedGroup", group.getName());
                        } else {
                            deviceInfo.put("assignedGroup", null);
                        }
                    } else {
                        deviceInfo.put("assignedGroup", null);
                    }
                    result.add(deviceInfo);
                }
            }
        } else if ("uninstalled".equals(type)) {
            Collection<Group> groups = storage.getObjects(Group.class, new Request(
                    new Columns.All(),
                    new Condition.Permission(User.class, getUserId(), Group.class)));
            for (Group group : groups) {
                if (group.getUnassigned() != 0) {
                    Collection<Device> devices = storage.getObjects(Device.class, new Request(
                            new Columns.All(),
                            new Condition.Equals("groupid", group.getUnassigned())));
                    for (Device device : devices) {
                        if (deviceNameFilter != null && !device.getName().toLowerCase().
                                contains(deviceNameFilter.toLowerCase())) {
                            continue;
                        }
                        if (imeiFilter != null && !device.getUniqueId().toLowerCase().
                                contains(imeiFilter.toLowerCase())) {
                            continue;
                        }
                        Group assignedGroup = storage.getObject(Group.class, new Request(
                                new Columns.All(),
                                new Condition.Equals("id", group.getUnassigned())));
                        Map<String, Object> deviceInfo = new HashMap<>();
                        deviceInfo.put("deviceName", device.getName());
                        deviceInfo.put("type", "uninstalled");
                        deviceInfo.put("imeiSerialNumber", device.getUniqueId());
//                        deviceInfo.put("deviceId", device.getId());
                        deviceInfo.put("assignedGroup", assignedGroup != null ? assignedGroup.getName() : null);
                        deviceInfo.put("lastCommunication", device.getLastUpdate());
                        result.add(deviceInfo);
                    }
                }
            }
        }
        return result;
    }


}

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

import jakarta.inject.Inject;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Position;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.WeeklyKmByGroupItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.*;
import java.util.stream.Collectors;

public class WeeklyKmByGroupReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final PermissionsService permissionsService;
    private final Storage storage;

    @Inject
    public WeeklyKmByGroupReportProvider(
            Config config, ReportUtils reportUtils, PermissionsService permissionsService, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.permissionsService = permissionsService;
        this.storage = storage;
    }

    private Position getLatestPosition(long deviceId, Date from, Date to) throws StorageException {
        return storage.getObject(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Between("fixTime", "from", from, "to", to)),
                new Order("fixTime", true, 1)));
    }

    private double calculateWeeklyKm(long deviceId, Date weekStart, Date weekEnd, Date previousWeekStart,
                                     Date previousWeekEnd)
            throws StorageException {
        Position thisWeekPosition = getLatestPosition(deviceId, weekStart, weekEnd);
        Position previousWeekPosition = getLatestPosition(deviceId, previousWeekStart, previousWeekEnd);
        if (thisWeekPosition != null && previousWeekPosition != null) {
            double thisWeekTotalDistance = thisWeekPosition.getDouble(Position.KEY_TOTAL_DISTANCE);
            double previousWeekTotalDistance = previousWeekPosition.getDouble(Position.KEY_TOTAL_DISTANCE);
            double weeklyDistance = thisWeekTotalDistance - previousWeekTotalDistance;
            return weeklyDistance > 0 ? weeklyDistance : 0;
        } else if (thisWeekPosition != null) {
            return thisWeekPosition.getDouble(Position.KEY_TOTAL_DISTANCE);
        }
        return 0;
    }

    public Collection<WeeklyKmByGroupItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        long weekDuration = to.getTime() - from.getTime();
        cal.setTimeInMillis(from.getTime() - weekDuration);
        Date previousWeekStart = cal.getTime();
        Date previousWeekEnd = from;

        Map<Long, WeeklyKmByGroupItem> groupDataMap = new HashMap<>();

        Collection<Device> devices = DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds);

        for (Device device : devices) {
            long deviceGroupId = device.getGroupId();
            if (deviceGroupId == 0) {
                continue;
            }

            double weeklyKm = calculateWeeklyKm(
                    device.getId(),
                    from,
                    to,
                    previousWeekStart,
                    previousWeekEnd
            );

            WeeklyKmByGroupItem groupItem = groupDataMap.get(deviceGroupId);
            if (groupItem == null) {
                groupItem = new WeeklyKmByGroupItem();
                groupItem.setGroupId(deviceGroupId);
                Group group = storage.getObject(Group.class, new Request(
                        new Columns.All(),
                        new Condition.Equals("id", deviceGroupId)));
                if (group != null) {
                    groupItem.setGroupName(group.getName());
                } else {
                    groupItem.setGroupName("Unknown Group");
                }
                groupItem.setWeeklyDistanceTraveled(0);
                groupItem.setDeviceCount(0);
                groupDataMap.put(deviceGroupId, groupItem);
            }

            groupItem.setWeeklyDistanceTraveled(groupItem.getWeeklyDistanceTraveled() + weeklyKm);
            groupItem.setDeviceCount(groupItem.getDeviceCount() + 1);
        }

        return groupDataMap.values().stream()
                .sorted(Comparator.comparing(WeeklyKmByGroupItem::getGroupName))
                .collect(Collectors.toList());
    }
}

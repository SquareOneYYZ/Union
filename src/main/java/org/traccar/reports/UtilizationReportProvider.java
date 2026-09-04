package org.traccar.reports;

import jakarta.inject.Inject;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.reports.model.UtilizationItem;
import org.traccar.reports.model.UtilizationResponse;
import org.traccar.reports.model.UtilizationTotal;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UtilizationReportProvider {

    @Inject
    private Storage storage;

    public UtilizationResponse getUtilization(
            long userId,
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to) throws StorageException {

        Collection<Device> allDevices = storage.getObjects(Device.class, new Request(
                new Columns.All(),
                new Condition.Permission(User.class, userId, Device.class)));

        Stream<Device> stream = allDevices.stream();
        if (groupIds != null && !groupIds.isEmpty()) {
            stream = stream.filter(d -> groupIds.contains(d.getGroupId()));
        }
        if (deviceIds != null && !deviceIds.isEmpty()) {
            stream = stream.filter(d -> deviceIds.contains(d.getId()));
        }
        List<Device> targetDevices = stream.collect(Collectors.toList());

        if (targetDevices.isEmpty()) {
            return new UtilizationResponse();
        }

        Set<Long> neededGroupIds = targetDevices.stream()
                .map(Device::getGroupId)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        Map<Long, String> groupNameMap = new HashMap<>();
        for (long gid : neededGroupIds) {
            Group group = storage.getObject(Group.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("id", gid)));
            if (group != null) {
                groupNameMap.put(gid, group.getName());
            }
        }

        double totalRangeSeconds = (from != null && to != null)
                ? (to.getTime() - from.getTime()) / 1000.0
                : 0.0;

        List<UtilizationItem> results = new ArrayList<>();

        for (Device device : targetDevices) {
            List<Position> positions = fetchPositions(device.getId(), from, to);
            if (positions.size() < 2) {
                continue;
            }

            double uptimeSeconds  = 0;
            double activeSeconds  = 0;
            double trackedSeconds = 0;
            double firstTotalDistance = 0;
            double lastTotalDistance  = 0;
            boolean firstSet = false;

            for (int i = 0; i < positions.size() - 1; i++) {
                Position curr = positions.get(i);
                Position next = positions.get(i + 1);

                if (!firstSet) {
                    Object td = curr.getAttributes().get(Position.KEY_TOTAL_DISTANCE);
                    if (td instanceof Number) {
                        firstTotalDistance = ((Number) td).doubleValue();
                        firstSet = true;
                    }
                }

                Object tdNext = next.getAttributes().get(Position.KEY_TOTAL_DISTANCE);
                if (tdNext instanceof Number) {
                    lastTotalDistance = ((Number) tdNext).doubleValue();
                }

                long gapMs = next.getFixTime().getTime() - curr.getFixTime().getTime();
                if (gapMs <= 0) {
                    continue;
                }

                double gapSeconds = gapMs / 1000.0;
                trackedSeconds += gapSeconds;

                Object ignition = curr.getAttributes().get(Position.KEY_IGNITION);
                if (Boolean.TRUE.equals(ignition)) {
                    uptimeSeconds += gapSeconds;
                }

                if (curr.getSpeed() > 0) {
                    activeSeconds += gapSeconds;
                }
            }

            double mileageMeters = lastTotalDistance - firstTotalDistance;
            if (mileageMeters < 0) {
                mileageMeters = 0;
            }

            double denominator = totalRangeSeconds > 0 ? totalRangeSeconds : trackedSeconds;

            UtilizationItem item = new UtilizationItem();
            item.setDeviceId(device.getId());
            item.setDeviceName(device.getName());
            item.setGroupId(device.getGroupId());
            item.setGroupName(groupNameMap.getOrDefault(device.getGroupId(), null));
            item.setUptimeHours(round2(uptimeSeconds / 3600.0));
            item.setUptimePercent(denominator > 0
                    ? round2((uptimeSeconds / denominator) * 100.0)
                    : 0.0);
            item.setActiveHours(round2(activeSeconds / 3600.0));
            item.setMileageKm(round2(mileageMeters / 1000.0));

            results.add(item);
        }

        results.sort(Comparator
                .comparingLong(UtilizationItem::getGroupId)
                .thenComparingLong(UtilizationItem::getDeviceId));

        double totalUptimeHours = results.stream().mapToDouble(UtilizationItem::getUptimeHours).sum();
        double totalActiveHours = results.stream().mapToDouble(UtilizationItem::getActiveHours).sum();
        double totalMileageKm   = results.stream().mapToDouble(UtilizationItem::getMileageKm).sum();
        double totalUptimePercent = results.isEmpty() ? 0.0
                : round2(results.stream()
                .mapToDouble(UtilizationItem::getUptimePercent)
                .average()
                .orElse(0.0));

        UtilizationTotal total = new UtilizationTotal();
        total.setUptimeHours(round2(totalUptimeHours));
        total.setUptimePercent(totalUptimePercent);
        total.setActiveHours(round2(totalActiveHours));
        total.setMileageKm(round2(totalMileageKm));

        UtilizationResponse response = new UtilizationResponse();
        response.setTotal(total);
        response.setDeviceDetails(results);

        return response;
    }

    private List<Position> fetchPositions(long deviceId, Date from, Date to)
            throws StorageException {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(new Condition.Equals("deviceId", deviceId));
        if (from != null) {
            conditions.add(new Condition.Compare("fixTime", ">=", "from", from));
        }
        if (to != null) {
            conditions.add(new Condition.Compare("fixTime", "<=", "to", to));
        }
        return new ArrayList<>(storage.getObjects(Position.class, new Request(
                new Columns.All(),
                Condition.merge(conditions),
                new Order("fixTime", false, 0))));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

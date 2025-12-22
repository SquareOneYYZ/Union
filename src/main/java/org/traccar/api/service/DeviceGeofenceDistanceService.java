package org.traccar.api.service;

import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.DeviceGeofenceDistanceDto;

import jakarta.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class DeviceGeofenceDistanceService {

    public Collection<DeviceGeofenceDistanceDto> calculateSegments(Collection<DeviceGeofenceDistance> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        // Group by device and geofence
        Map<String, List<DeviceGeofenceDistance>> groupedRecords = records.stream()
                .collect(Collectors.groupingBy(
                        record -> record.getDeviceId() + "_" + record.getGeofenceId(),
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> {
                                    list.sort(Comparator.comparing(DeviceGeofenceDistance::getDeviceTime));
                                    return list;
                                }
                        )
                ));

        List<DeviceGeofenceDistanceDto> result = new ArrayList<>();

        for (List<DeviceGeofenceDistance> group : groupedRecords.values()) {
            processGroup(group, result);
        }

        // Sort by deviceId, geofenceId, startTime
        result.sort(Comparator
                .comparingLong(DeviceGeofenceDistanceDto::getDeviceId)
                .thenComparingLong(DeviceGeofenceDistanceDto::getGeofenceId)
                .thenComparing(DeviceGeofenceDistanceDto::getStartTime,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        return result;
    }

    private void processGroup(List<DeviceGeofenceDistance> group, List<DeviceGeofenceDistanceDto> result) {
        for (int i = 0; i < group.size(); i++) {
            DeviceGeofenceDistance current = group.get(i);

            if ("enter".equals(current.getType())) {
                // Create "Inside" segment: from this enter to next exit
                DeviceGeofenceDistance nextExit = findNextExit(group, i);

                DeviceGeofenceDistanceDto dto = new DeviceGeofenceDistanceDto();
                dto.setDeviceId(current.getDeviceId());
                dto.setGeofenceId(current.getGeofenceId());
                dto.setType("Inside");
                dto.setStartTime(current.getDeviceTime());
                dto.setOdoStart(current.getTotalDistance());  // meters

                if (nextExit != null) {
                    dto.setEndTime(nextExit.getDeviceTime());
                    dto.setOdoEnd(nextExit.getTotalDistance());  // meters
                    dto.setDistance(dto.getOdoEnd() - dto.getOdoStart());  // meters
                    dto.setOpen(false);
                } else {
                    // Still inside, segment is open
                    dto.setOpen(true);
                }
                result.add(dto);

            } else if ("exit".equals(current.getType())) {
                // Create "Outside" segment: from this exit to next enter
                DeviceGeofenceDistance nextEnter = findNextEnter(group, i);

                if (nextEnter != null) {
                    DeviceGeofenceDistanceDto dto = new DeviceGeofenceDistanceDto();
                    dto.setDeviceId(current.getDeviceId());
                    dto.setGeofenceId(current.getGeofenceId());
                    dto.setType("Outside");
                    dto.setStartTime(current.getDeviceTime());
                    dto.setEndTime(nextEnter.getDeviceTime());
                    dto.setOdoStart(current.getTotalDistance());  // meters
                    dto.setOdoEnd(nextEnter.getTotalDistance());  // meters
                    dto.setDistance(dto.getOdoEnd() - dto.getOdoStart());  // meters
                    dto.setOpen(false);
                    result.add(dto);
                }
                // If no next enter, device is still outside - could optionally create open segment
            }
        }
    }

    private DeviceGeofenceDistance findNextExit(List<DeviceGeofenceDistance> group, int currentIndex) {
        for (int i = currentIndex + 1; i < group.size(); i++) {
            if ("exit".equals(group.get(i).getType())) {
                return group.get(i);
            }
        }
        return null;
    }

    private DeviceGeofenceDistance findNextEnter(List<DeviceGeofenceDistance> group, int currentIndex) {
        for (int i = currentIndex + 1; i < group.size(); i++) {
            if ("enter".equals(group.get(i).getType())) {
                return group.get(i);
            }
        }
        return null;
    }

    // Keep old method name for backwards compatibility, but delegate to new logic
    public Collection<DeviceGeofenceDistanceDto> calculateDistances(Collection<DeviceGeofenceDistance> records) {
        return calculateSegments(records);
    }
}

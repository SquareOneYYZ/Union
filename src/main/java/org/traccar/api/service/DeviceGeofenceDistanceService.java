package org.traccar.api.service;

import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.DeviceGeofenceDistanceDto;

import jakarta.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class DeviceGeofenceDistanceService {

    public Collection<DeviceGeofenceDistanceDto> calculateDistances(Collection<DeviceGeofenceDistance> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<DeviceGeofenceDistance>> groupedRecords = records.stream()
                .collect(Collectors.groupingBy(
                        record -> record.getDeviceId() + "_" + record.getGeofenceId(),
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> {
                                    list.sort(Comparator.comparingLong(DeviceGeofenceDistance::getPositionId));
                                    return list;
                                }
                        )
                ));

        List<DeviceGeofenceDistanceDto> result = new ArrayList<>();

        for (List<DeviceGeofenceDistance> group : groupedRecords.values()) {
            processGroup(group, result);
        }
        result.sort(Comparator
                .comparingLong(DeviceGeofenceDistanceDto::getDeviceId)
                .thenComparingLong(DeviceGeofenceDistanceDto::getGeofenceId)
                .thenComparingLong(DeviceGeofenceDistanceDto::getPositionId));

        return result;
    }

    private void processGroup(List<DeviceGeofenceDistance> group, List<DeviceGeofenceDistanceDto> result) {
        DeviceGeofenceDistance previousExit = null;

        for (int i = 0; i < group.size(); i++) {
            DeviceGeofenceDistance current = group.get(i);
            DeviceGeofenceDistanceDto dto = new DeviceGeofenceDistanceDto(current);

            if ("enter".equals(current.getType())) {
                if (previousExit != null) {
                    double distanceOutside = current.getTotalDistance() - previousExit.getTotalDistance();

                    for (int j = result.size() - 1; j >= 0; j--) {
                        DeviceGeofenceDistanceDto exitDto = result.get(j);
                        if (exitDto.getId() == previousExit.getId()) {
                            exitDto.setDistanceOutside(distanceOutside);
                            break;
                        }
                    }
                }

                DeviceGeofenceDistance nextExit = findNextExit(group, i);
                if (nextExit != null) {
                    double distanceInside = nextExit.getTotalDistance() - current.getTotalDistance();
                    dto.setDistanceInside(distanceInside);
                }

            } else if ("exit".equals(current.getType())) {
                DeviceGeofenceDistance previousEnter = findPreviousEnter(group, i);
                if (previousEnter != null) {
                    double distanceInside = current.getTotalDistance() - previousEnter.getTotalDistance();
                    dto.setDistanceInside(distanceInside);
                }

                previousExit = current;
            }

            result.add(dto);
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

    private DeviceGeofenceDistance findPreviousEnter(List<DeviceGeofenceDistance> group, int currentIndex) {
        for (int i = currentIndex - 1; i >= 0; i--) {
            if ("enter".equals(group.get(i).getType())) {
                return group.get(i);
            }
        }
        return null;
    }

    public DeviceGeofenceDistanceDto calculateDistanceForSingle(
            DeviceGeofenceDistance record,
            Collection<DeviceGeofenceDistance> allRelatedRecords) {
        
        List<DeviceGeofenceDistance> relatedRecords = allRelatedRecords.stream()
                .filter(r -> r.getDeviceId() == record.getDeviceId() 
                        && r.getGeofenceId() == record.getGeofenceId())
                .sorted(Comparator.comparingLong(DeviceGeofenceDistance::getPositionId))
                .collect(Collectors.toList());

        Collection<DeviceGeofenceDistanceDto> calculated = calculateDistances(relatedRecords);

        return calculated.stream()
                .filter(dto -> dto.getId() == record.getId())
                .findFirst()
                .orElse(new DeviceGeofenceDistanceDto(record));
    }
}

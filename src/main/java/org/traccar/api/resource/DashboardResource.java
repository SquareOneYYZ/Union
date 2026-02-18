
package org.traccar.api.resource;

import jakarta.ws.rs.*;
import org.traccar.api.BaseResource;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.User;
import org.traccar.model.UserRestrictions;
import org.traccar.reports.DayNightKmReportProvider;
import org.traccar.reports.WeeklyKmByGroupReportProvider;
import org.traccar.reports.model.DashboardEventItem;
import org.traccar.reports.model.DayNightKmSummary;
import org.traccar.reports.model.VehicleStatusSummary;
import org.traccar.reports.model.WeeklyKmByGroupItem;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Path("dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DashboardResource extends BaseResource {

    @Inject
    private WeeklyKmByGroupReportProvider weeklyKmByGroupReportProvider;

    @Inject
    private DayNightKmReportProvider dayNightKmReportProvider;

    @Path("weeklykm")
    @GET
    public Collection<WeeklyKmByGroupItem> getWeeklyKmByGroup(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "weeklykm", from, to, deviceIds, groupIds);
        return weeklyKmByGroupReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("vehiclestatus")
    @GET
    public VehicleStatusSummary getVehicleStatusSummary() throws StorageException {
        Collection<Device> devices = storage.getObjects(Device.class, new Request(
                new Columns.All(),
                new Condition.Permission(User.class, getUserId(), Device.class)));

        VehicleStatusSummary summary = new VehicleStatusSummary();
        Date currentTime = new Date();
        long currentMillis = currentTime.getTime();
        long twoMinutes = 2 * 60 * 1000;
        long oneHour = 60 * 60 * 1000;
        long twentyFourHours = 24 * 60 * 60 * 1000;
        int totalOnline = 0;
        int totalOffline = 0;
        int totalUnknown = 0;
        int totalDriving = 0;
        int totalParked = 0;
        int totalInactive = 0;
        int totalNoData = 0;
        for (Device device : devices) {
            String status = device.getStatus();
            if (Device.STATUS_ONLINE.equals(status)) {
                totalOnline++;
            } else if (Device.STATUS_OFFLINE.equals(status)) {
                totalOffline++;
            } else {
                totalUnknown++;
            }
            Date lastUpdate = device.getLastUpdate();
            if (lastUpdate == null) {
                totalNoData++;
            } else {
                long timeDiff = currentMillis - lastUpdate.getTime();
                if (timeDiff < twoMinutes) {
                    totalDriving++;
                } else if (timeDiff >= twoMinutes && timeDiff < oneHour) {
                    totalParked++;
                } else if (timeDiff >= twentyFourHours) {
                    totalInactive++;
                }
            }
        }
        summary.setTotalOnline(totalOnline);
        summary.setTotalOffline(totalOffline);
        summary.setTotalUnknown(totalUnknown);
        summary.setTotalDriving(totalDriving);
        summary.setTotalParked(totalParked);
        summary.setTotalInactive(totalInactive);
        summary.setTotalNoData(totalNoData);
        return summary;
    }

    @Path("daynightkm")
    @GET
    public DayNightKmSummary getDayNightKm(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "daynightkm", from, to, deviceIds, groupIds);
        return dayNightKmReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("events")
    @GET
    public Collection<Event> getEvents(
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("eventType") String eventType,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {

        Collection<Device> devices = storage.getObjects(Device.class, new Request(
                new Columns.All(),
                new Condition.Permission(User.class, getUserId(), Device.class)));

        Set<Long> deviceIds = devices.stream()
                .map(Device::getId)
                .collect(Collectors.toSet());

        if (deviceIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Condition> conditions = new ArrayList<>();
        if (eventType != null && !eventType.isEmpty()) {
            conditions.add(new Condition.Equals("type", eventType));
        }
        if (from != null) {
            conditions.add(new Condition.Compare("eventTime", ">=", "from", from));
        }
        if (to != null) {
            conditions.add(new Condition.Compare("eventTime", "<=", "to", to));
        }

        Collection<Event> allEvents;
        if (conditions.isEmpty()) {
            allEvents = storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    new Order("eventTime", true, limit * 10)));
        } else {
            allEvents = storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    Condition.merge(conditions),
                    new Order("eventTime", true, limit * 10)));
        }

        return allEvents.stream()
                .filter(event -> deviceIds.contains(event.getDeviceId()))
                .limit(limit)
                .collect(Collectors.toList());
    }



    @Path("recentEvents")
    @GET
    public Collection<DashboardEventItem> getEvents(
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("deviceId") List<Long> deviceIdsFilter,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("eventType") String eventType,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        Collection<Device> devices = storage.getObjects(Device.class, new Request(
                new Columns.All(),
                new Condition.Permission(User.class, getUserId(), Device.class)));

        Stream<Device> deviceStream = devices.stream();
        if (deviceIdsFilter != null && !deviceIdsFilter.isEmpty()) {
            deviceStream = deviceStream.filter(d -> deviceIdsFilter.contains(d.getId()));
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            deviceStream = deviceStream.filter(d -> groupIds.contains(d.getGroupId()));
        }
        Map<Long, Device> deviceMap = deviceStream
                .collect(Collectors.toMap(Device::getId, d -> d));
        if (deviceMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<Condition> conditions = new ArrayList<>();
        List<Condition> deviceConditions = deviceMap.keySet().stream()
                .map(id -> new Condition.Equals("deviceId", id))
                .collect(Collectors.toList());

        Condition deviceCondition = null;
        if (!deviceConditions.isEmpty()) {
            deviceCondition = deviceConditions.get(0);
            for (int i = 1; i < deviceConditions.size(); i++) {
                deviceCondition = new Condition.Or(deviceCondition, deviceConditions.get(i));
            }
            conditions.add(deviceCondition);
        }
        if (eventType != null && !eventType.isEmpty()) {
            conditions.add(new Condition.Equals("type", eventType));
        }
        if (from != null && to != null) {
            conditions.add(new Condition.Between(
                    "eventTime",
                    "from", from,
                    "to", to));
        } else if (from != null) {
            conditions.add(new Condition.Compare("eventTime", ">=", "from", from));
        } else if (to != null) {
            conditions.add(new Condition.Compare("eventTime", "<=", "to", to));
        }
        Collection<Event> allEvents;
        if (conditions.isEmpty()) {
            allEvents = storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    new Order("eventTime", true, limit)
            ));
        } else {
            allEvents = storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    Condition.merge(conditions),
                    new Order("eventTime", true, limit)
            ));
        }
        return allEvents.stream()
                .filter(event -> deviceMap.containsKey(event.getDeviceId()))
                .limit(limit)
                .map(event -> {
                    DashboardEventItem item = new DashboardEventItem();
                    item.setId(event.getId());
                    item.setAttributes(event.getAttributes());
                    item.setType(event.getType());
                    item.setEventTime(event.getEventTime());
                    item.setDeviceId(event.getDeviceId());
                    item.setPositionId(event.getPositionId());
                    item.setGeofenceId(event.getGeofenceId());
                    item.setMaintenanceId(event.getMaintenanceId());

                    Device device = deviceMap.get(event.getDeviceId());
                    if (device != null) {
                        item.setDeviceName(device.getName());
                    }

                    return item;
                })
                .collect(Collectors.toList());

    }

}

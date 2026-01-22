
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
}

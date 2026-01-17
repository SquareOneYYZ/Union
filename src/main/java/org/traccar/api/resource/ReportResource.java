/*
 * Copyright 2016 - 2023 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.api.SimpleObjectResource;
import org.traccar.helper.LogAction;
import org.traccar.helper.ReportPeriodUtil;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.Report;
import org.traccar.model.ReportHistory;
import org.traccar.model.UserRestrictions;
import org.traccar.reports.CombinedReportProvider;
import org.traccar.reports.DeviceGeofenceDistanceReportProvider;
import org.traccar.reports.DevicesReportProvider;
import org.traccar.reports.EventsReportProvider;
import org.traccar.reports.RouteReportProvider;
import org.traccar.reports.StopsReportProvider;
import org.traccar.reports.SummaryReportProvider;
import org.traccar.reports.TripsReportProvider;
import org.traccar.reports.common.ReportExecutor;
import org.traccar.reports.common.ReportMailer;
import org.traccar.reports.model.CombinedReportItem;
import org.traccar.reports.model.StopReportItem;
import org.traccar.reports.model.SummaryReportItem;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.util.*;

@Path("reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReportResource extends SimpleObjectResource<Report> {

    private static final String EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Inject
    private CombinedReportProvider combinedReportProvider;

    @Inject
    private EventsReportProvider eventsReportProvider;

    @Inject
    private RouteReportProvider routeReportProvider;

    @Inject
    private StopsReportProvider stopsReportProvider;

    @Inject
    private SummaryReportProvider summaryReportProvider;

    @Inject
    private TripsReportProvider tripsReportProvider;

    @Inject
    private DevicesReportProvider devicesReportProvider;

    @Inject
    private ReportMailer reportMailer;

    @Inject
    private DeviceGeofenceDistanceReportProvider deviceGeofenceDistanceReportProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReportResource() {
        super(Report.class, "description");
    }

    private void saveReportHistory(
            long userId,
            String reportType,
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            Map<String, Object> additionalParams) {
        try {
            String deviceIdsJson = (deviceIds != null && !deviceIds.isEmpty())
                    ? objectMapper.writeValueAsString(deviceIds) : null;
            String groupIdsJson = (groupIds != null && !groupIds.isEmpty())
                    ? objectMapper.writeValueAsString(groupIds) : null;
            String additionalParamsJson = (additionalParams != null && !additionalParams.isEmpty())
                    ? objectMapper.writeValueAsString(additionalParams) : null;
            String period = ReportPeriodUtil.detectPeriod(from, to);

            Date normalizedFrom = from != null ? new Date(from.getTime() / 1000 * 1000) : null;
            Date normalizedTo = to != null ? new Date(to.getTime() / 1000 * 1000) : null;

            var condition = new org.traccar.storage.query.Condition.And(
                    new org.traccar.storage.query.Condition.Equals("userId", userId),
                    new org.traccar.storage.query.Condition.Equals("reportType", reportType)
            );

            var request = new org.traccar.storage.query.Request(
                    new org.traccar.storage.query.Columns.All(),
                    condition,
                    new org.traccar.storage.query.Order("id", true, 1)
            );
            Collection<ReportHistory> existingReports = storage.getObjects(ReportHistory.class, request);
            boolean shouldSave = true;
            org.slf4j.LoggerFactory.getLogger(ReportResource.class)
                    .info("Found {} existing reports for user {} and type {}",
                            existingReports != null ? existingReports.size() : 0, userId, reportType);

            if (existingReports != null && !existingReports.isEmpty()) {
                ReportHistory lastReport = existingReports.iterator().next();

                Date lastFromDate = lastReport.getFromDate() != null
                        ? new Date(lastReport.getFromDate().getTime() / 1000 * 1000) : null;
                Date lastToDate = lastReport.getToDate() != null
                        ? new Date(lastReport.getToDate().getTime() / 1000 * 1000) : null;

                org.slf4j.LoggerFactory.getLogger(ReportResource.class)
                        .info("Last report: deviceIds={}, groupIds={}, from={}, to={}, params={}, period={}",
                                lastReport.getDeviceIds(), lastReport.getGroupIds(),
                                lastFromDate, lastToDate,
                                lastReport.getAdditionalParams(), lastReport.getPeriod());

                org.slf4j.LoggerFactory.getLogger(ReportResource.class)
                        .info("New report: deviceIds={}, groupIds={}, from={}, to={}, params={}, period={}",
                                deviceIdsJson, groupIdsJson, normalizedFrom, normalizedTo,
                                additionalParamsJson, period);

                boolean sameDeviceIds = Objects.equals(deviceIdsJson, lastReport.getDeviceIds());
                boolean sameGroupIds = Objects.equals(groupIdsJson, lastReport.getGroupIds());
                boolean sameFromDate = Objects.equals(normalizedFrom, lastFromDate);
                boolean sameToDate = Objects.equals(normalizedTo, lastToDate);
                boolean sameAdditionalParams = Objects.equals(additionalParamsJson, lastReport.getAdditionalParams());
                boolean samePeriod = Objects.equals(period, lastReport.getPeriod());

                org.slf4j.LoggerFactory.getLogger(ReportResource.class)
                        .info("Comparison: deviceIds={}, groupIds={}, from={}, to={}, params={}, period={}",
                                sameDeviceIds, sameGroupIds, sameFromDate, sameToDate,
                                sameAdditionalParams, samePeriod);

                if (sameDeviceIds && sameGroupIds && sameFromDate && sameToDate
                        && sameAdditionalParams && samePeriod) {
                    shouldSave = false;
                    org.slf4j.LoggerFactory.getLogger(ReportResource.class)
                            .info("DUPLICATE DETECTED - Skipping report history entry");
                }
            }
            if (shouldSave) {
                ReportHistory history = new ReportHistory();
                history.setUserId(userId);
                history.setReportType(reportType);
                history.setGeneratedAt(new Date());
                history.setDeviceIds(deviceIdsJson);
                history.setGroupIds(groupIdsJson);
                history.setFromDate(normalizedFrom);
                history.setToDate(normalizedTo);
                history.setPeriod(period);
                history.setAdditionalParams(additionalParamsJson);

                storage.addObject(history, new org.traccar.storage.query.Request(
                        new org.traccar.storage.query.Columns.Exclude("id")));

                org.slf4j.LoggerFactory.getLogger(ReportResource.class)
                        .info("NEW REPORT SAVED - user {} type {}", userId, reportType);
            }

        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ReportResource.class)
                    .warn("Failed to save report history", e);
            e.printStackTrace();
        }
    }

    private Response executeReport(long userId, boolean mail, ReportExecutor executor) {
        if (mail) {
            reportMailer.sendAsync(userId, executor);
            return Response.noContent().build();
        } else {
            StreamingOutput stream = output -> {
                try {
                    executor.execute(output);
                } catch (StorageException e) {
                    throw new WebApplicationException(e);
                }
            };
            return Response.ok(stream)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.xlsx").build();
        }
    }

    @Path("combined")
    @GET
    public Collection<CombinedReportItem> getCombined(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "combined", from, to, deviceIds, groupIds);
        saveReportHistory(getUserId(), "combined", deviceIds, groupIds, from, to, null);
        return combinedReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("route")
    @GET
    public Collection<Position> getRoute(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "route", from, to, deviceIds, groupIds);
        saveReportHistory(getUserId(), "route", deviceIds, groupIds, from, to, null);
        return routeReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("route")
    @GET
    @Produces(EXCEL)
    public Response getRouteExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            LogAction.report(getUserId(), false, "route", from, to, deviceIds, groupIds);
            routeReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("route/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getRouteExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") final List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getRouteExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("events")
    @GET
    public Collection<Event> getEvents(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "events", from, to, deviceIds, groupIds);
        Map<String, Object> additionalParams = new HashMap<>();
        if (types != null && !types.isEmpty()) {
            additionalParams.put("types", types);
        }
        if (alarms != null && !alarms.isEmpty()) {
            additionalParams.put("alarms", alarms);
        }
        saveReportHistory(getUserId(), "events", deviceIds, groupIds, from, to, additionalParams);
        return eventsReportProvider.getObjects(getUserId(), deviceIds, groupIds, types, alarms, from, to);
    }

    @Path("events")
    @GET
    @Produces(EXCEL)
    public Response getEventsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            LogAction.report(getUserId(), false, "events", from, to, deviceIds, groupIds);
            eventsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, types, alarms, from, to);
        });
    }

    @Path("events/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getEventsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getEventsExcel(deviceIds, groupIds, types, alarms, from, to, type.equals("mail"));
    }

    @Path("summary")
    @GET
    public Collection<SummaryReportItem> getSummary(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "summary", from, to, deviceIds, groupIds);
        Map<String, Object> additionalParams = new HashMap<>();
        additionalParams.put("daily", daily);
        saveReportHistory(getUserId(), "summary", deviceIds, groupIds, from, to, additionalParams);
        return summaryReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to, daily);
    }

    @Path("summary")
    @GET
    @Produces(EXCEL)
    public Response getSummaryExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            LogAction.report(getUserId(), false, "summary", from, to, deviceIds, groupIds);
            summaryReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to, daily);
        });
    }

    @Path("summary/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getSummaryExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily,
            @PathParam("type") String type) throws StorageException {
        return getSummaryExcel(deviceIds, groupIds, from, to, daily, type.equals("mail"));
    }

    @Path("trips")
    @GET
    public Collection<TripReportItem> getTrips(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "trips", from, to, deviceIds, groupIds);
        saveReportHistory(getUserId(), "trips", deviceIds, groupIds, from, to, null);
        return tripsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("trips")
    @GET
    @Produces(EXCEL)
    public Response getTripsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            LogAction.report(getUserId(), false, "trips", from, to, deviceIds, groupIds);
            tripsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("trips/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getTripsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getTripsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("stops")
    @GET
    public Collection<StopReportItem> getStops(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "stops", from, to, deviceIds, groupIds);
        saveReportHistory(getUserId(), "stops", deviceIds, groupIds, from, to, null);
        return stopsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("stops")
    @GET
    @Produces(EXCEL)
    public Response getStopsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            LogAction.report(getUserId(), false, "stops", from, to, deviceIds, groupIds);
            stopsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("stops/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getStopsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getStopsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("devices/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getDevicesExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("group") List<Long> groupIds,
            @QueryParam("model") String model,
            @QueryParam("status") String status,
            @QueryParam("name") String name,
            @QueryParam("identifier") String identifier,
            @QueryParam("vin") String vin,
            @PathParam("type") String type) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), type.equals("mail"), stream -> {
            devicesReportProvider.getExcel(stream, getUserId(),
                    deviceIds, groupIds, model, status, name, identifier, vin);
        });
    }

    @Path("devicegeofencedistances")
    @GET
    @Produces(EXCEL)
    public Response getDeviceGeofenceDistancesExcel(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("geofenceId") long geofenceId,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            List<Long> deviceIds = deviceId > 0 ? List.of(deviceId) : List.of();
            LogAction.report(getUserId(), mail, "devicegeofencedistances", from, to, deviceIds, List.of());
            deviceGeofenceDistanceReportProvider.getExcel(stream, getUserId(), deviceId, geofenceId, from, to);
        });
    }

    @Path("devicegeofencedistances/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getDeviceGeofenceDistancesExcel(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("geofenceId") long geofenceId,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getDeviceGeofenceDistancesExcel(deviceId, geofenceId, from, to, type.equals("mail"));
    }

}

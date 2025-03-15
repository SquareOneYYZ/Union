/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Andrey Kunitsyn (andrey@traccar.org)
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
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.DeviceReportSection;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
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
import java.util.List;

public class TripsReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final Storage storage;

    @Inject
    public TripsReportProvider(Config config, ReportUtils reportUtils, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.storage = storage;
    }

    public Collection<TripReportItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<TripReportItem> result = new ArrayList<>();
        for (Device device: DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            result.addAll(reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class));
            Collection<TripReportItem> trips = reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class);
            if (trips != null && !trips.isEmpty()) {
            double totalDistance = 0;
            Date startTime = null;
            Date endTime = null;

            for (TripReportItem trip : trips) {
                totalDistance += trip.getDistance();
                if (startTime == null || trip.getStartTime().before(startTime)) {
                    startTime = trip.getStartTime();
                }
                if (endTime == null || trip.getEndTime().after(endTime)) {
                    endTime = trip.getEndTime();
                }
            }

            TripReportItem summaryRow = new TripReportItem();
            summaryRow.setDeviceName("Summary");
            summaryRow.setStartTime(startTime);
            summaryRow.setEndTime(endTime);
            summaryRow.setDistance(Math.max(0, totalDistance));

            result.addAll(trips);
            result.add(summaryRow);
        }
        }
        return result;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException, IOException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<DeviceReportSection> devicesTrips = new ArrayList<>();
        ArrayList<String> sheetNames = new ArrayList<>();
        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            Collection<TripReportItem> trips = reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class);
            if (trips == null) {
                continue;
            }
            DeviceReportSection deviceTrips = new DeviceReportSection();
            deviceTrips.setDeviceName(device.getName());
            sheetNames.add(WorkbookUtil.createSafeSheetName(deviceTrips.getDeviceName()));
            if (device.getGroupId() > 0) {
                Group group = storage.getObject(Group.class, new Request(
                        new Columns.All(), new Condition.Equals("id", device.getGroupId())));
                if (group != null) {
                    deviceTrips.setGroupName(group.getName());
                }
            }

            double totalDistance = 0;
            Date startTime = null;
            Date endTime = null;
             double totalSpentFuel = 0;
             double maxSpeed = 0;
             double totalSpeed = 0;
             int tripCount = 0;

            for (TripReportItem trip : trips) {
                totalDistance += trip.getDistance();
                totalSpentFuel += trip.getSpentFuel();
                maxSpeed = Math.max(maxSpeed, trip.getMaxSpeed());
                totalSpeed += trip.getAverageSpeed();
                tripCount++;

                if (startTime == null || trip.getStartTime().before(startTime)) {
                    startTime = trip.getStartTime();
                }

                if (endTime == null || trip.getEndTime().after(endTime)) {
                    endTime = trip.getEndTime();
                }


            }

            if (!trips.isEmpty() && startTime != null && endTime != null) {
                TripReportItem headerRow = new TripReportItem();
                 headerRow.setDeviceName("");               
                 headerRow.setStartAddress("SUMMARY REPORT");          
                 headerRow.setEndAddress("SUMMARY REPORT");             
                 headerRow.setStartTime(null);             
                 headerRow.setEndTime(null);                

                 headerRow.setDistance(0);                 
                 headerRow.setAverageSpeed(0);              
                 headerRow.setMaxSpeed(0);                 
                 headerRow.setSpentFuel(0);                 
                 headerRow.setDriverName(null);            
                 headerRow.setDuration(0);


                TripReportItem summaryRow = new TripReportItem();
                summaryRow.setDeviceName("Summary");
                summaryRow.setStartTime(startTime);
                summaryRow.setEndTime(endTime);
                summaryRow.setDistance(Math.max(0, totalDistance));
                summaryRow.setSpentFuel(totalSpentFuel);
                summaryRow.setMaxSpeed(maxSpeed);
                summaryRow.setAverageSpeed(tripCount > 0 ? totalSpeed / tripCount : 0);
                
                // Add summary row to trips collection
                if (trips instanceof ArrayList) {
                    ((ArrayList<TripReportItem>)trips).add(headerRow);
                    ((ArrayList<TripReportItem>)trips).add(summaryRow);
                } else {
                    List<TripReportItem> mutableTrips = new ArrayList<>(trips);
                    mutableTrips.add(headerRow);
                    mutableTrips.add(summaryRow);
                    trips = mutableTrips;
                }
            }





            deviceTrips.setObjects(trips);
            devicesTrips.add(deviceTrips);
        }

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "trips.xlsx").toFile();
        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("devices", devicesTrips);
            context.putVar("sheetNames", sheetNames);
            context.putVar("from", from);
            context.putVar("to", to);
            reportUtils.processTemplateWithSheets(inputStream, outputStream, context);
        }
    }

}

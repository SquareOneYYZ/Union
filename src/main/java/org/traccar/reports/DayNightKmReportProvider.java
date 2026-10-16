
package org.traccar.reports;

import jakarta.inject.Inject;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.DayNightKmSummary;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;

import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class DayNightKmReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final PermissionsService permissionsService;
    private final Storage storage;

    @Inject
    public DayNightKmReportProvider(
            Config config, ReportUtils reportUtils, PermissionsService permissionsService, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.permissionsService = permissionsService;
        this.storage = storage;
    }

    private boolean isDaytime(Date fixTime) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(fixTime);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 6 && hour < 18;
    }

    private DayNightKmSummary calculateDeviceKm(long deviceId, Date from, Date to) throws StorageException {
        DayNightKmSummary summary = new DayNightKmSummary();
        double daytimeKm = 0;
        double nighttimeKm = 0;

        List<Position> positions = PositionUtil.getPositions(storage, deviceId, from, to);

        if (positions.isEmpty()) {
            summary.setDaytimeKm(0);
            summary.setNighttimeKm(0);
            return summary;
        }

        for (int i = 1; i < positions.size(); i++) {
            Position previous = positions.get(i - 1);
            Position current = positions.get(i);

            double previousDistance = previous.getDouble(Position.KEY_TOTAL_DISTANCE);
            double currentDistance = current.getDouble(Position.KEY_TOTAL_DISTANCE);
            double segmentDistance = currentDistance - previousDistance;

            if (segmentDistance > 0) {
                if (isDaytime(current.getFixTime())) {
                    daytimeKm += segmentDistance;
                } else {
                    nighttimeKm += segmentDistance;
                }
            }
        }

        summary.setDaytimeKm(daytimeKm / 1000.0);
        summary.setNighttimeKm(nighttimeKm / 1000.0);

        return summary;
    }

    public DayNightKmSummary getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        DayNightKmSummary totalSummary = new DayNightKmSummary();
        double totalDaytimeKm = 0;
        double totalNighttimeKm = 0;

        Collection<Device> devices = DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds);

        for (Device device : devices) {
            DayNightKmSummary deviceSummary = calculateDeviceKm(device.getId(), from, to);
            totalDaytimeKm += deviceSummary.getDaytimeKm();
            totalNighttimeKm += deviceSummary.getNighttimeKm();
        }

        totalSummary.setDaytimeKm(totalDaytimeKm);
        totalSummary.setNighttimeKm(totalNighttimeKm);

        return totalSummary;
    }
}

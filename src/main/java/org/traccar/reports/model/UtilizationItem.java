package org.traccar.reports.model;

import java.util.Date;

public class UtilizationItem {

    private long deviceId;
    private String deviceName;
    private long groupId;
    private String groupName;
    private double uptimeHours;
    private double uptimePercent;
    private double activeHours;
    private double mileageKm;

    public long getDeviceId() { return deviceId; }
    public void setDeviceId(long deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public long getGroupId() { return groupId; }
    public void setGroupId(long groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public double getUptimeHours() { return uptimeHours; }
    public void setUptimeHours(double uptimeHours) { this.uptimeHours = uptimeHours; }

    public double getUptimePercent() { return uptimePercent; }
    public void setUptimePercent(double uptimePercent) { this.uptimePercent = uptimePercent; }

    public double getActiveHours() { return activeHours; }
    public void setActiveHours(double activeHours) { this.activeHours = activeHours; }

    public double getMileageKm() { return mileageKm; }
    public void setMileageKm(double mileageKm) { this.mileageKm = mileageKm; }
}

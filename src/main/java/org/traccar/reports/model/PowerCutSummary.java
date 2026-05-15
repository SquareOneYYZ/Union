package org.traccar.reports.model;

import java.util.Date;

public class PowerCutSummary {
    private long deviceId;
    private String uniqueId;
    private String deviceName;
    private String groupName;
    private Date firstPowerCut;
    private Date lastPowerCut;
    private long totalPowerCutEvents;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public void setUniqueId(String uniqueId) {
        this.uniqueId = uniqueId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Date getFirstPowerCut() {
        return firstPowerCut;
    }

    public void setFirstPowerCut(Date firstPowerCut) {
        this.firstPowerCut = firstPowerCut;
    }

    public Date getLastPowerCut() {
        return lastPowerCut;
    }

    public void setLastPowerCut(Date lastPowerCut) {
        this.lastPowerCut = lastPowerCut;
    }

    public long getTotalPowerCutEvents() {
        return totalPowerCutEvents;
    }

    public void setTotalPowerCutEvents(long totalPowerCutEvents) {
        this.totalPowerCutEvents = totalPowerCutEvents;
    }
}

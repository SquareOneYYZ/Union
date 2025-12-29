
package org.traccar.reports.model;

public class WeeklyKmByGroupItem {

    private long groupId;
    public long getGroupId() {
        return groupId;
    }

    public void setGroupId(long groupId) {
        this.groupId = groupId;
    }

    private String groupName;

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    private double weeklyDistanceTraveled;

    public double getWeeklyDistanceTraveled() {
        return weeklyDistanceTraveled;
    }

    public void setWeeklyDistanceTraveled(double weeklyDistanceTraveled) {
        this.weeklyDistanceTraveled = weeklyDistanceTraveled;
    }

    private int deviceCount;

    public int getDeviceCount() {
        return deviceCount;
    }

    public void setDeviceCount(int deviceCount) {
        this.deviceCount = deviceCount;
    }
}

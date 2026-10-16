package org.traccar.reports.model;

public class UtilizationTotal {

    private double uptimeHours;
    private double uptimePercent;
    private double activeHours;
    private double mileageKm;

    public double getUptimeHours() {
        return uptimeHours;
    }
    public void setUptimeHours(double uptimeHours) {
        this.uptimeHours = uptimeHours;
    }

    public double getUptimePercent() {
        return uptimePercent;
    }
    public void setUptimePercent(double uptimePercent) {
        this.uptimePercent = uptimePercent;
    }

    public double getActiveHours() {
        return activeHours;
    }
    public void setActiveHours(double activeHours) {
        this.activeHours = activeHours;
    }

    public double getMileageKm() {
        return mileageKm;
    }
    public void setMileageKm(double mileageKm) {
        this.mileageKm = mileageKm;
    }
}

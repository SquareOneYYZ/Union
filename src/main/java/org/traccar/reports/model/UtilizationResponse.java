package org.traccar.reports.model;

import java.util.List;

public class UtilizationResponse {

    private UtilizationTotal total;
    private List<UtilizationItem> deviceDetails;

    public UtilizationTotal getTotal() { return total; }
    public void setTotal(UtilizationTotal total) { this.total = total; }

    public List<UtilizationItem> getDeviceDetails() { return deviceDetails; }
    public void setDeviceDetails(List<UtilizationItem> deviceDetails) { this.deviceDetails = deviceDetails; }
}

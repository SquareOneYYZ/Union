package org.traccar.model;

import org.traccar.storage.StorageName;

import java.util.Date;


@StorageName("tc_vin_mappings")
public class VinMapping extends ExtendedModel {

    private long organizationid;

    public long getOrganizationId() {
        return organizationid;
    }

    public void setOrganizationId(long organizationid) {
        this.organizationid = organizationid;
    }


    private String imei;

    public String getImei() {
        return imei;
    }

    public void setImei(String imei) {
        this.imei = imei != null ? imei.trim() : null;
    }

    private String vin;

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin != null ? vin.trim().toUpperCase() : null;
    }

    private long deviceid;

    public long getDeviceId() {
        return deviceid;
    }

    public void setDeviceId(long deviceid) {
        this.deviceid = deviceid;
    }

    private long groupid;

    public long getGroupId() {
        return groupid;
    }

    public void setGroupId(long groupid) {
        this.groupid = groupid;
    }

    private Date appliedat;

    public Date getAppliedAt() {
        return appliedat;
    }

    public void setAppliedAt(Date appliedat) {
        this.appliedat = appliedat;
    }


}

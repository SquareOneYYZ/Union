/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import org.traccar.storage.QueryIgnore;
import org.traccar.storage.StorageName;

import java.util.Date;

@StorageName("tc_device_geofence_segment")
public class DeviceGeofenceSegment extends BaseModel {

    private long deviceId;
    private long geofenceId;
    private String type; // "inside" or "outside"
    private long enterPositionId;
    private Long exitPositionId;
    private Date enterTime;
    private Date exitTime;
    private double odoStart;
    private Double odoEnd;
    private Double distance;
    private boolean open;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public long getGeofenceId() {
        return geofenceId;
    }

    public void setGeofenceId(long geofenceId) {
        this.geofenceId = geofenceId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getEnterPositionId() {
        return enterPositionId;
    }

    public void setEnterPositionId(long enterPositionId) {
        this.enterPositionId = enterPositionId;
    }

    public Long getExitPositionId() {
        return exitPositionId;
    }

    public void setExitPositionId(Long exitPositionId) {
        this.exitPositionId = exitPositionId;
    }

    public Date getEnterTime() {
        return enterTime;
    }

    public void setEnterTime(Date enterTime) {
        this.enterTime = enterTime;
    }

    public Date getExitTime() {
        return exitTime;
    }

    public void setExitTime(Date exitTime) {
        this.exitTime = exitTime;
    }

    // Alias getters for frontend/Excel compatibility
    @QueryIgnore
    @JsonGetter("startTime")
    public Date getStartTime() {
        return enterTime;
    }

    @QueryIgnore
    @JsonGetter("endTime")
    public Date getEndTime() {
        return exitTime;
    }

    public double getOdoStart() {
        return odoStart;
    }

    public void setOdoStart(double odoStart) {
        this.odoStart = odoStart;
    }

    public Double getOdoEnd() {
        return odoEnd;
    }

    public void setOdoEnd(Double odoEnd) {
        this.odoEnd = odoEnd;
    }

    public Double getDistance() {
        return distance;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }

    public boolean getOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }
}

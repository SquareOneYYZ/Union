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
package org.traccar.reports.model;

public class VehicleStatusSummary {

    private int totalOnline;
    private int totalOffline;
    private int totalUnknown;
    private int totalDriving;
    private int totalParked;
    private int totalInactive;
    private int totalNoData;

    public int getTotalOnline() {
        return totalOnline;
    }

    public void setTotalOnline(int totalOnline) {
        this.totalOnline = totalOnline;
    }

    public int getTotalOffline() {
        return totalOffline;
    }

    public void setTotalOffline(int totalOffline) {
        this.totalOffline = totalOffline;
    }

    public int getTotalUnknown() {
        return totalUnknown;
    }

    public void setTotalUnknown(int totalUnknown) {
        this.totalUnknown = totalUnknown;
    }

    public int getTotalDriving() {
        return totalDriving;
    }

    public void setTotalDriving(int totalDriving) {
        this.totalDriving = totalDriving;
    }

    public int getTotalParked() {
        return totalParked;
    }

    public void setTotalParked(int totalParked) {
        this.totalParked = totalParked;
    }

    public int getTotalInactive() {
        return totalInactive;
    }

    public void setTotalInactive(int totalInactive) {
        this.totalInactive = totalInactive;
    }

    public int getTotalNoData() {
        return totalNoData;
    }

    public void setTotalNoData(int totalNoData) {
        this.totalNoData = totalNoData;
    }
}

/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar.vindecoder;

public class VinDecoder {

    private String vin;
    private String make;
    private String manufacturer;
    private String model;
    private String modelYear;
    private String vehicleType;
    private String Trim;
    private String BodyClass;
    private String DriveType;
    private String BatteryType;
    private String FuelTypePrimary;
    private String DisplacementL;
    private String EngineCylinders;
    private String EngineHP;

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getMake() {
        return make;
    }

    public void setMake(String make) {
        this.make = make;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModelYear() {
        return modelYear;
    }

    public void setModelYear(String modelYear) {
        this.modelYear = modelYear;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public String getTrim() {
        return Trim;
    }

    public void setTrim(String trim) {
        Trim = trim;
    }

    public String getBodyClass() {
        return BodyClass;
    }

    public void setBodyClass(String bodyClass) {
        BodyClass = bodyClass;
    }

    public String getDriveType() {
        return DriveType;
    }

    public void setDriveType(String driveType) {
        DriveType = driveType;
    }

    public String getBatteryType() {
        return BatteryType;
    }

    public void setBatteryType(String batteryType) {
        BatteryType = batteryType;
    }

    public String getFuelTypePrimary() {
        return FuelTypePrimary;
    }

    public void setFuelTypePrimary(String fuelTypePrimary) {
        FuelTypePrimary = fuelTypePrimary;
    }

    public String getDisplacementL() {
        return DisplacementL;
    }

    public void setDisplacementL(String displacementL) {
        DisplacementL = displacementL;
    }

    public String getEngineCylinders() {
        return EngineCylinders;
    }

    public void setEngineCylinders(String engineCylinders) {
        EngineCylinders = engineCylinders;
    }

    public String getEngineHP() {
        return EngineHP;
    }

    public void setEngineHP(String engineHP) {
        EngineHP = engineHP;
    }


}

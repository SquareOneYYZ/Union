package org.traccar.model;

import org.traccar.storage.StorageName;

import java.math.BigDecimal;
import java.util.Date;

/**
 * One priced (or knowingly unpriced) toll traversal, written once by {@code TaskTollPricing}.
 *
 * <p>{@code amount} is {@code null} when the price is unknown and {@code reason} says why; it is
 * never stored as zero. Ids ending in "Id" are written as NULL when 0 by the storage layer, so a
 * traversal with no enter event stores a NULL {@code enterEventId}. Coordinates are only meaningful
 * when the matching position id is set.
 */
@StorageName("tc_toll_traversal")
public class TollTraversal extends BaseModel {

    private long deviceId;
    private String facilityId;
    private long enterEventId;
    private long exitEventId;
    private long enterPositionId;
    private long exitPositionId;
    private Date enterTime;
    private Date exitTime;
    private double enterLatitude;
    private double enterLongitude;
    private double exitLatitude;
    private double exitLongitude;
    private double distance;
    private String direction;
    private String dayType;
    private String band;
    private String vehicleClass;
    private String paymentMethod;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private int calcVersion;
    private String cardValidFrom;
    private Date pricedAt;
    private long mergedIntoId;
    private String inputs;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public String getFacilityId() {
        return facilityId;
    }

    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }

    public long getEnterEventId() {
        return enterEventId;
    }

    public void setEnterEventId(long enterEventId) {
        this.enterEventId = enterEventId;
    }

    public long getExitEventId() {
        return exitEventId;
    }

    public void setExitEventId(long exitEventId) {
        this.exitEventId = exitEventId;
    }

    public long getEnterPositionId() {
        return enterPositionId;
    }

    public void setEnterPositionId(long enterPositionId) {
        this.enterPositionId = enterPositionId;
    }

    public long getExitPositionId() {
        return exitPositionId;
    }

    public void setExitPositionId(long exitPositionId) {
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

    public double getEnterLatitude() {
        return enterLatitude;
    }

    public void setEnterLatitude(double enterLatitude) {
        this.enterLatitude = enterLatitude;
    }

    public double getEnterLongitude() {
        return enterLongitude;
    }

    public void setEnterLongitude(double enterLongitude) {
        this.enterLongitude = enterLongitude;
    }

    public double getExitLatitude() {
        return exitLatitude;
    }

    public void setExitLatitude(double exitLatitude) {
        this.exitLatitude = exitLatitude;
    }

    public double getExitLongitude() {
        return exitLongitude;
    }

    public void setExitLongitude(double exitLongitude) {
        this.exitLongitude = exitLongitude;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getDayType() {
        return dayType;
    }

    public void setDayType(String dayType) {
        this.dayType = dayType;
    }

    public String getBand() {
        return band;
    }

    public void setBand(String band) {
        this.band = band;
    }

    public String getVehicleClass() {
        return vehicleClass;
    }

    public void setVehicleClass(String vehicleClass) {
        this.vehicleClass = vehicleClass;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getCalcVersion() {
        return calcVersion;
    }

    public void setCalcVersion(int calcVersion) {
        this.calcVersion = calcVersion;
    }

    public String getCardValidFrom() {
        return cardValidFrom;
    }

    public void setCardValidFrom(String cardValidFrom) {
        this.cardValidFrom = cardValidFrom;
    }

    public Date getPricedAt() {
        return pricedAt;
    }

    public void setPricedAt(Date pricedAt) {
        this.pricedAt = pricedAt;
    }

    /** Id of the traversal this fragment was merged into; 0 (stored NULL) when not merged. */
    public long getMergedIntoId() {
        return mergedIntoId;
    }

    public void setMergedIntoId(long mergedIntoId) {
        this.mergedIntoId = mergedIntoId;
    }

    /** JSON: everything the estimate was computed from, so a later calc version can re-estimate. */
    public String getInputs() {
        return inputs;
    }

    public void setInputs(String inputs) {
        this.inputs = inputs;
    }
}

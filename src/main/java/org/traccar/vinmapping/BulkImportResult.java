package org.traccar.vinmapping;


public class BulkImportResult {

    public enum Status {
        CREATED,
        REJECTED
    }

    private final String imei;
    private final String vin;
    private final Status status;
    private final String message;

    public BulkImportResult(String imei, String vin, Status status, String message) {
        this.imei = imei;
        this.vin = vin;
        this.status = status;
        this.message = message;
    }

    public String getImei() {
        return imei;
    }

    public String getVin() {
        return vin;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

}

package org.traccar.vinmapping;


public class BulkImportResult {

    public enum Status {
        CREATED,
        UPDATED,
        REJECTED
    }

    private final int row;
    private final String imei;
    private final String vin;
    private final Status status;
    private final String message;

    public BulkImportResult(int row, String imei, String vin, Status status, String message) {
        this.row = row;
        this.imei = imei;
        this.vin = vin;
        this.status = status;
        this.message = message;
    }

    public int getRow() {
        return row;
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

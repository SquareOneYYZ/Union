package org.traccar.dtos;

public class RowResult {
    public int row;
    public String name;
    public String uniqueId;
    public boolean success;
    public String status;
    public String message;

    public RowResult(int row, String name, String uniqueId,
                     boolean success, String status, String message) {
        this.row      = row;
        this.name     = name;
        this.uniqueId = uniqueId;
        this.success  = success;
        this.status   = status;
        this.message  = message;
    }
}

package org.traccar.dtos;

public class RowResult {
    private int row;
    private String name;
    private String uniqueId;
    private boolean success;
    private String status;
    private String message;

    public RowResult(int row, String name, String uniqueId,
                     boolean success, String status, String message) {
        this.row      = row;
        this.name     = name;
        this.uniqueId = uniqueId;
        this.success  = success;
        this.status   = status;
        this.message  = message;
    }

    public int getRow() {
        return row;
    }

    public String getName() {
        return name;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setUniqueId(String uniqueId) {
        this.uniqueId = uniqueId;
    }

}

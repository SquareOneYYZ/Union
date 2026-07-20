package org.traccar.dtos;

import java.util.List;

public class BulkUploadResponse {
    private int totalRows;
    private int successCount;
    private int failureCount;
    private List<RowResult> rows;

    public BulkUploadResponse(List<RowResult> rows) {
        this.rows         = rows;
        this.totalRows    = rows.size();
        this.successCount = (int) rows.stream().filter(RowResult::isSuccess).count();
        this.failureCount = totalRows - successCount;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public List<RowResult> getRows() {
        return rows;
    }
}

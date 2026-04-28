package org.traccar.dtos;

import java.util.List;

public class BulkUploadResponse {
    public int totalRows;
    public int successCount;
    public int failureCount;
    public List<RowResult> rows;

    public BulkUploadResponse(List<RowResult> rows) {
        this.rows         = rows;
        this.totalRows    = rows.size();
        this.successCount = (int) rows.stream().filter(r -> r.success).count();
        this.failureCount = totalRows - successCount;
    }
}

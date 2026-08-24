package org.traccar.vinmapping;

import java.util.List;

public class BulkImportSummary {

    private final int totalProcessed;
    private final int successCount;
    private final int failedCount;
    private final List<BulkImportResult> results;

    public BulkImportSummary(List<BulkImportResult> results) {
        this.results = results;
        this.totalProcessed = results.size();
        this.successCount = (int) results.stream()
                .filter(r -> r.getStatus() == BulkImportResult.Status.CREATED
                        || r.getStatus() == BulkImportResult.Status.UPDATED)
                .count();
        this.failedCount = totalProcessed - successCount;
    }

    public int getTotalProcessed() {
        return totalProcessed;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public List<BulkImportResult> getResults() {
        return results;
    }
}

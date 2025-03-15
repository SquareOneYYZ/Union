package org.traccar.reports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.reports.common.ReportUtils;

import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class TripsReportProviderTest extends BaseTest {

    @Mock
    private Storage storage;
    
    @Mock
    private Config config;
    
    @Mock
    private ReportUtils reportUtils;

    private TripsReportProvider provider;

    @BeforeEach
    public void setup() throws Exception {
        openMocks(this);
        provider = new TripsReportProvider(config, reportUtils, storage);
    }

    @Test
    public void testSummaryCalculation() throws StorageException {
        // Create test data
        Collection<TripReportItem> trips = new ArrayList<>();
        Date startTime = new Date(1000);
        Date endTime = new Date(2000);
        
        TripReportItem trip1 = new TripReportItem();
        trip1.setStartTime(startTime);
        trip1.setEndTime(new Date(1500));
        trip1.setDistance(100);
        trips.add(trip1);

        TripReportItem trip2 = new TripReportItem();
        trip2.setStartTime(new Date(1500));
        trip2.setEndTime(endTime);
        trip2.setDistance(200);
        trips.add(trip2);

        // Mock device
        Device device = new Device();
        device.setId(1);
        device.setName("Test Device");
        
        when(storage.getObjects(eq(Device.class), any())).thenReturn(List.of(device));
        when(reportUtils.detectTripsAndStops(eq(device), any(Date.class), any(Date.class), eq(TripReportItem.class)))
            .thenReturn(new ArrayList<>(trips));  // Mock the trips detection

        // Get report items
        Collection<TripReportItem> result = provider.getObjects(1L, List.of(1L), null, startTime, endTime);

        // Verify summary row lol 
        TripReportItem summary = result.stream()
                .filter(item -> "Summary".equals(item.getDeviceName()))
                .findFirst()
                .orElse(null);

        assertNotNull(summary, "Summary row should exist");
        assertEquals(300, summary.getDistance(), "Total distance should be correct");
        assertEquals(startTime, summary.getStartTime(), "Start time should be earliest");
        assertEquals(endTime, summary.getEndTime(), "End time should be latest");
    }

    @Test
    public void testEmptyTrips() throws StorageException {
        // Test with empty trips collection
        when(storage.getObjects(eq(Device.class), any())).thenReturn(List.of(new Device()));
        when(reportUtils.detectTripsAndStops(any(), any(), any(), any())).thenReturn(null);
        
        Collection<TripReportItem> result = provider.getObjects(1L, List.of(1L), null, new Date(), new Date());
        assertTrue(result.isEmpty(), "Result should be empty for no trips , check the implementation");
    }
}
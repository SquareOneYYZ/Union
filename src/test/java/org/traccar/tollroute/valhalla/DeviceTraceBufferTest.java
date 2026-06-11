package org.traccar.tollroute.valhalla;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DeviceTraceBufferTest {


    @Test
    void addsPointAndReturnsIt() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5);
        List<DeviceTraceBuffer.TracePoint> pts = buf.add(1L, 43.0, -79.0, 1000L);
        assertEquals(1, pts.size());
        assertEquals(43.0, pts.get(0).lat());
        assertEquals(-79.0, pts.get(0).lon());
    }

    @Test
    void capsAtCapacity() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(3);
        for (int i = 0; i < 10; i++) {
            buf.add(1L, 43.0 + i * 0.001, -79.0, 1000L + i);
        }
        List<DeviceTraceBuffer.TracePoint> pts = buf.get(1L);
        assertEquals(3, pts.size());
        assertEquals(1000L + 7, pts.get(0).unixTimeSec());
        assertEquals(1000L + 9, pts.get(2).unixTimeSec());
    }

    @Test
    void returnsEmptyListForUnknownDevice() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5);
        assertTrue(buf.get(999L).isEmpty());
    }


    @Test
    void dropsOutOfOrderPoint() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5);
        buf.add(1L, 43.0, -79.0, 1000L);
        buf.add(1L, 43.001, -79.001, 999L);
        assertEquals(1, buf.get(1L).size());
        assertEquals(1000L, buf.get(1L).get(0).unixTimeSec());
    }

    @Test
    void acceptsSameTimestamp() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5);
        buf.add(1L, 43.0, -79.0, 1000L);
        buf.add(1L, 43.001, -79.001, 1000L);
        assertEquals(2, buf.get(1L).size());
    }


    @Test
    void resetsBufferOnTeleport() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5, 50.0);
        buf.add(1L, 43.0, -79.0, 1000L);
        buf.add(1L, 43.001, -79.001, 1001L);
        List<DeviceTraceBuffer.TracePoint> pts = buf.add(1L, 45.5, -73.5, 1002L);
        assertEquals(1, pts.size(), "Buffer should reset on teleport");
        assertEquals(45.5, pts.get(0).lat());
    }

    @Test
    void doesNotResetOnNormalMovement() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5, 50.0);
        buf.add(1L, 43.0, -79.0, 1000L);
        buf.add(1L, 43.001, -79.001, 1001L);  // ~130m — fine
        assertEquals(2, buf.get(1L).size());
    }



    @Test
    void separateBuffersPerDevice() {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5);
        buf.add(1L, 43.0, -79.0, 1000L);
        buf.add(2L, 44.0, -78.0, 1000L);
        assertEquals(1, buf.get(1L).size());
        assertEquals(1, buf.get(2L).size());
        assertEquals(43.0, buf.get(1L).get(0).lat());
        assertEquals(44.0, buf.get(2L).get(0).lat());
    }


    @Test
    void concurrentAddsSameDeviceNoExceptions() throws InterruptedException {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(10);
        int threads = 8;
        int pointsEach = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < pointsEach; i++) {
                        long ts = (long) threadId * pointsEach * 10 + (long) i * 10;
                        buf.add(42L, 43.0 + i * 0.0001, -79.0, ts);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertTrue(errors.isEmpty(), "No exceptions during concurrent add: " + errors);
        assertTrue(buf.get(42L).size() <= 10, "Buffer must not exceed capacity");
    }


    @Test
    void evictsIdleDevices() throws InterruptedException {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5);
        buf.add(1L, 43.0, -79.0, 1000L);
        buf.add(2L, 43.0, -79.0, 1000L);
        assertEquals(2, buf.size());

        Thread.sleep(50);
        int evicted = buf.evictIdle(10);
        assertEquals(2, evicted);
        assertEquals(0, buf.size());
    }

    @Test
    void doesNotEvictActiveDevices() throws InterruptedException {
        DeviceTraceBuffer buf = new DeviceTraceBuffer(5);
        buf.add(1L, 43.0, -79.0, 1000L);
        Thread.sleep(30);
        buf.add(1L, 43.001, -79.001, 1001L);
        int evicted = buf.evictIdle(20);
        assertEquals(0, evicted);
    }
}

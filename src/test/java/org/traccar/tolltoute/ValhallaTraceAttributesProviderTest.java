package org.traccar.tolltoute;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.tollroute.TollRouteProvider;
import org.traccar.tollroute.ValhallaTraceAttributesProvider;
import org.traccar.tollroute.TollData;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ValhallaTraceAttributesProviderTest {

    private final Client client = ClientBuilder.newClient();

    private ValhallaTraceAttributesProvider buildProvider() {
        Config config = new Config();
        return new ValhallaTraceAttributesProvider(config, client);
    }

    private TollData runSinglePointTrace(
            ValhallaTraceAttributesProvider provider,
            long deviceId,
            double lat, double lon, long unixTime) throws Exception {

        provider.bufferPoint(deviceId, lat, lon, unixTime);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TollData> result = new AtomicReference<>();
        AtomicReference<Throwable> error  = new AtomicReference<>();

        provider.getTollRoute(lat, lon, new TollRouteProvider.TollRouteProviderCallback() {
            @Override
            public void onSuccess(TollData tollData) {
                result.set(tollData);
                latch.countDown();
            }
            @Override
            public void onFailure(Throwable e) {
                error.set(e);
                latch.countDown();
            }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Valhalla request timed out");
        if (error.get() != null) {
            throw new RuntimeException("Valhalla call failed", error.get());
        }
        return result.get();
    }


    @Disabled("Live Valhalla demo – remove @Disabled to run")
    @Test
    public void testKnownTollRoad407ETR() throws Exception {
        ValhallaTraceAttributesProvider provider = buildProvider();
        long deviceId = 1001L;
        long t = 1700000000L;

        provider.bufferPoint(deviceId, 43.7615, -79.6950, t);
        provider.bufferPoint(deviceId, 43.7620, -79.6920, t + 15);
        provider.bufferPoint(deviceId, 43.7625, -79.6890, t + 30);
        provider.bufferPoint(deviceId, 43.7628, -79.6860, t + 45);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TollData> result = new AtomicReference<>();
        AtomicReference<Throwable> error  = new AtomicReference<>();

        provider.getTollRoute(43.7628, -79.6860, new TollRouteProvider.TollRouteProviderCallback() {
            @Override
            public void onSuccess(TollData tollData) { result.set(tollData); latch.countDown(); }
            @Override
            public void onFailure(Throwable e) { error.set(e); latch.countDown(); }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Valhalla request timed out");
        assertNull(error.get(), () -> "Valhalla failed: " + error.get());

        TollData td = result.get();
        assertNotNull(td);
        System.out.println("407 ETR result: toll=" + td.getToll() + " surface=" + td.getSurface()
                + " highway=" + td.getHighway());
        assertTrue(td.getToll(), "Expected toll=true for 407 ETR");
    }

    @Disabled("Live Valhalla demo – remove @Disabled to run")
    @Test
    public void testFrontageRoadNotToll() throws Exception {
        ValhallaTraceAttributesProvider provider = buildProvider();
        long deviceId = 1002L;
        long t = 1700100000L;

        provider.bufferPoint(deviceId, 43.7530, -79.5450, t);
        provider.bufferPoint(deviceId, 43.7535, -79.5420, t + 20);
        provider.bufferPoint(deviceId, 43.7540, -79.5390, t + 40);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TollData> result = new AtomicReference<>();
        AtomicReference<Throwable> error  = new AtomicReference<>();

        provider.getTollRoute(43.7540, -79.5390, new TollRouteProvider.TollRouteProviderCallback() {
            @Override
            public void onSuccess(TollData tollData) { result.set(tollData); latch.countDown(); }
            @Override
            public void onFailure(Throwable e) { error.set(e); latch.countDown(); }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Valhalla request timed out");
        assertNull(error.get(), () -> "Valhalla failed: " + error.get());

        TollData td = result.get();
        assertNotNull(td);
        System.out.println("Frontage road result: toll=" + td.getToll() + " surface=" + td.getSurface()
                + " highway=" + td.getHighway());
        assertFalse(td.getToll(), "Expected toll=false for frontage road");
    }

    @Test
    public void testProcessResponseTollEdge() {
        Config config = new Config();
        ValhallaTraceAttributesProvider provider = new ValhallaTraceAttributesProvider(config, client);

        ValhallaTraceAttributesProvider.TraceResponse response =
                new ValhallaTraceAttributesProvider.TraceResponse();

        ValhallaTraceAttributesProvider.ValhallaEdge edge =
                new ValhallaTraceAttributesProvider.ValhallaEdge();
        edge.toll      = true;
        edge.surface   = "paved_smooth";
        edge.roadClass = "motorway";
        edge.beginShapeIndex = 0;
        edge.endShapeIndex   = 2;

        response.edges = List.of(edge);

        ValhallaTraceAttributesProvider.MatchedPoint mp =
                new ValhallaTraceAttributesProvider.MatchedPoint();
        mp.edgeIndex              = 0;
        mp.distanceFromTracePoint = 3.0;

        response.matchedPoints = List.of(mp);

        List<ValhallaTraceAttributesProvider.ShapePoint> shape = List.of(
                new ValhallaTraceAttributesProvider.ShapePoint(43.76, -79.69, 1700000000L)
        );

        TollData td = provider.processResponse(response, shape);
        assertTrue(td.getToll(),      "Expected toll=true");
        assertEquals("paved_smooth",  td.getSurface());
        assertEquals("motorway",      td.getHighway());
    }

    @Test
    public void testProcessResponseSnapTooFar() {
        Config config = new Config();
        ValhallaTraceAttributesProvider provider = new ValhallaTraceAttributesProvider(config, client);

        ValhallaTraceAttributesProvider.TraceResponse response =
                new ValhallaTraceAttributesProvider.TraceResponse();

        ValhallaTraceAttributesProvider.ValhallaEdge edge =
                new ValhallaTraceAttributesProvider.ValhallaEdge();
        edge.toll    = true;
        edge.surface = "paved_smooth";
        response.edges = List.of(edge);

        ValhallaTraceAttributesProvider.MatchedPoint mp =
                new ValhallaTraceAttributesProvider.MatchedPoint();
        mp.edgeIndex              = 0;
        mp.distanceFromTracePoint = 999.0; // Way too far

        response.matchedPoints = List.of(mp);

        List<ValhallaTraceAttributesProvider.ShapePoint> shape = List.of(
                new ValhallaTraceAttributesProvider.ShapePoint(43.76, -79.69, 1700000000L)
        );

        TollData td = provider.processResponse(response, shape);
        assertFalse(td.getToll(), "Snap distance too large — should return toll=false");
    }

    @Test
    public void testProcessResponseNullResponse() {
        Config config = new Config();
        ValhallaTraceAttributesProvider provider = new ValhallaTraceAttributesProvider(config, client);
        TollData td = provider.processResponse(null, List.of());
        assertFalse(td.getToll());
    }
}

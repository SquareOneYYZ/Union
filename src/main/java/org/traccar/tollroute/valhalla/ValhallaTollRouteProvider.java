package org.traccar.tollroute.valhalla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;
import org.traccar.tollroute.TollData;
import org.traccar.tollroute.TollRouteProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Singleton
public final class ValhallaTollRouteProvider implements TollRouteProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ValhallaTollRouteProvider.class);

    private final Client                  client;
    private final String                  valhallaUrl;
    private final int                     searchRadius;
    private final int                     gpsAccuracy;
    private final int                     callCadence;
    private final ObjectMapper            objectMapper;
    private final DeviceTraceBuffer       traceBuffer;
    private final TraceResultInterpreter  interpreter;

    private final Map<Long, Integer> callCounters = new ConcurrentHashMap<>();

    public ValhallaTollRouteProvider(Config config, Client client) {
        this.client        = client;
        this.objectMapper  = new ObjectMapper();

        String url = config.getString(Keys.VALHALLA_URL);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "tollRoute.valhalla.url must be set — no default provided. "
                    + "Use your self-hosted Valhalla endpoint.");
        }
        this.valhallaUrl  = url;
        this.searchRadius = config.getInteger(Keys.VALHALLA_SEARCH_RADIUS);
        this.gpsAccuracy  = config.getInteger(Keys.VALHALLA_GPS_ACCURACY);
        this.callCadence  = Math.max(1, config.getInteger(Keys.VALHALLA_CALL_CADENCE));

        int    bufferSize   = config.getInteger(Keys.VALHALLA_BUFFER_SIZE);
        double snapDistance = config.getInteger(Keys.VALHALLA_MAX_SNAP_DISTANCE);

        this.traceBuffer = new DeviceTraceBuffer(bufferSize);
        this.interpreter = new TraceResultInterpreter(snapDistance, new BillableTollRegistry(config));

        LOGGER.info(
                "ValhallaTollRouteProvider init — url={}, bufferSize={}, cadence=1/{}, "
                + "searchRadius={}m, snapDistance={}m",
                valhallaUrl, bufferSize, callCadence, searchRadius, (int) snapDistance);
    }


    @Override
    public void getTollRoute(Position position, TollRouteProviderCallback callback) {
        long deviceId = position.getDeviceId();

        long unixTimeSec = position.getFixTime() != null
                ? position.getFixTime().getTime() / 1000L
                : System.currentTimeMillis() / 1000L;

        List<DeviceTraceBuffer.TracePoint> buffered = traceBuffer.add(
                deviceId,
                position.getLatitude(),
                position.getLongitude(),
                unixTimeSec);

        int count = callCounters.merge(deviceId, 1, Integer::sum);
        if (count % callCadence != 0) {
            LOGGER.debug("ValhallaTollRouteProvider: cadence skip for deviceId={} (call #{})", deviceId, count);
            callback.onSuccess(new TollData(false, null, null, null, null, null));
            return;
        }

        if (buffered.size() < 2) {
            LOGGER.debug("ValhallaTollRouteProvider: buffer={} < 2 for deviceId={} — skipping",
                    buffered.size(), deviceId);
            callback.onSuccess(new TollData(false, null, null, null, null, null));
            return;
        }

        List<ValhallaRequest.ShapePoint> shape = toShapePoints(buffered);
        dispatchRequest(deviceId, shape, callback);
    }


    @Override
    public void getTollRoute(double latitude, double longitude, TollRouteProviderCallback callback) {
        LOGGER.debug("ValhallaTollRouteProvider: getTollRoute(lat,lon) called without Position — "
                + "use getTollRoute(Position, cb) for Valhalla. Returning empty.");
        callback.onSuccess(new TollData(false, null, null, null, null, null));
    }


    private void dispatchRequest(long deviceId,
                                  List<ValhallaRequest.ShapePoint> shape,
                                  TollRouteProviderCallback callback) {

        ValhallaRequest.Body requestBody = new ValhallaRequest.Body(
                shape,
                new ValhallaRequest.TraceOptions(searchRadius, gpsAccuracy));

        String json;
        try {
            json = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            LOGGER.warn("ValhallaTollRouteProvider: failed to serialise request for deviceId={}", deviceId, e);
            callback.onFailure(e);
            return;
        }

        LOGGER.debug("ValhallaTollRouteProvider: POST {} points for deviceId={}", shape.size(), deviceId);

        client.target(valhallaUrl)
                .request(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "ridesiq")
                .async()
                .post(Entity.json(json), new InvocationCallback<String>() {
                    @Override
                    public void completed(String responseJson) {
                        try {
                            ValhallaResponse.Body response =
                                    objectMapper.readValue(responseJson, ValhallaResponse.Body.class);
                            TollData result = interpreter.interpret(response, shape);
                            LOGGER.debug("ValhallaTollRouteProvider: deviceId={} toll={} surface={}",
                                    deviceId, result.getToll(), result.getSurface());
                            callback.onSuccess(result);
                        } catch (Exception e) {
                            LOGGER.warn("ValhallaTollRouteProvider: parse error for deviceId={}", deviceId, e);
                            callback.onFailure(e);
                        }
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        LOGGER.warn("ValhallaTollRouteProvider: HTTP error for deviceId={}: {}",
                                deviceId, throwable.getMessage());
                        callback.onFailure(throwable);
                    }
                });
    }

    private static List<ValhallaRequest.ShapePoint> toShapePoints(
            List<DeviceTraceBuffer.TracePoint> pts) {
        List<ValhallaRequest.ShapePoint> out = new ArrayList<>(pts.size());
        for (DeviceTraceBuffer.TracePoint p : pts) {
            out.add(new ValhallaRequest.ShapePoint(p.lat(), p.lon(), p.unixTimeSec()));
        }
        return out;
    }

    public DeviceTraceBuffer getTraceBuffer() {
        return traceBuffer;
    }
}

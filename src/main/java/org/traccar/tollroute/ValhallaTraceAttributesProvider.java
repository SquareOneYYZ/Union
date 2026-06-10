package org.traccar.tollroute;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Singleton
public class ValhallaTraceAttributesProvider implements TollRouteProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ValhallaTraceAttributesProvider.class);


    public static final class ShapePoint {
        @JsonProperty("lat")  private final double lat;
        @JsonProperty("lon")  private final double lon;
        @JsonProperty("time") private final long time;

        public ShapePoint(double lat, double lon, long time) {
            this.lat  = lat;
            this.lon  = lon;
            this.time = time;
        }
    }

    private static final class TraceOptions {
        @JsonProperty("search_radius") private final int searchRadius;
        @JsonProperty("gps_accuracy")  private final int gpsAccuracy;

        TraceOptions(int searchRadius, int gpsAccuracy) {
            this.searchRadius = searchRadius;
            this.gpsAccuracy  = gpsAccuracy;
        }
    }

    private static final class Filters {
        @JsonProperty("attributes") private final List<String> attributes;
        @JsonProperty("action")     private final String action = "include";

        Filters() {
            this.attributes = List.of(
                    "edge.way_id",
                    "edge.toll",
                    "edge.surface",
                    "edge.road_class",
                    "edge.speed_limit",
                    "edge.begin_heading",
                    "edge.end_heading",
                    "matched.point",
                    "matched.type",
                    "matched.edge_index",
                    "matched.distance_from_trace_point"
            );
        }
    }

    private static final class TraceRequest {
        @JsonProperty("shape")        private final List<ShapePoint> shape;
        @JsonProperty("costing")      private final String costing    = "auto";
        @JsonProperty("shape_match")  private final String shapeMatch = "map_snap";
        @JsonProperty("trace_options") private final TraceOptions traceOptions;
        @JsonProperty("filters")      private final Filters filters  = new Filters();

        TraceRequest(List<ShapePoint> shape, TraceOptions traceOptions) {
            this.shape        = shape;
            this.traceOptions = traceOptions;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ValhallaEdge {
        @JsonProperty("way_id")         private Long    wayId;
        @JsonProperty("toll")           private Boolean toll;
        @JsonProperty("surface")        private String  surface;
        @JsonProperty("road_class")     private String  roadClass;
        @JsonProperty("speed_limit")    private Integer speedLimit;
        @JsonProperty("begin_heading")  private Double  beginHeading;
        @JsonProperty("end_heading")    private Double  endHeading;
        @JsonProperty("begin_shape_index") private Integer beginShapeIndex;
        @JsonProperty("end_shape_index")   private Integer endShapeIndex;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MatchedPoint {
        @JsonProperty("lat")                         private double  lat;
        @JsonProperty("lon")                         private double  lon;
        @JsonProperty("type")                        private String  type;
        @JsonProperty("edge_index")                  private Integer edgeIndex;
        @JsonProperty("distance_from_trace_point")   private Double  distanceFromTracePoint;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TraceResponse {
        @JsonProperty("edges")          private List<ValhallaEdge>   edges;
        @JsonProperty("matched_points") private List<MatchedPoint>   matchedPoints;
    }


    private static final class BufferedPoint {
        private final double lat;
        private final double lon;
        private final long   unixTimeSec;

        BufferedPoint(double lat, double lon, long unixTimeSec) {
            this.lat         = lat;
            this.lon         = lon;
            this.unixTimeSec = unixTimeSec;
        }
    }


    private final Client       client;
    private final String       valhallaUrl;
    private final int          searchRadius;
    private final int          gpsAccuracy;
    private final int          bufferSize;
    private final int          maxSnapDistanceMetres;
    private final ObjectMapper objectMapper;


    private final Map<String, List<BufferedPoint>> deviceBuffers = new ConcurrentHashMap<>();

    public ValhallaTraceAttributesProvider(Config config, Client client) {
        this.client               = client;
        this.valhallaUrl          = config.getString(Keys.VALHALLA_URL);
        this.searchRadius         = config.getInteger(Keys.VALHALLA_SEARCH_RADIUS);
        this.gpsAccuracy          = config.getInteger(Keys.VALHALLA_GPS_ACCURACY);
        this.bufferSize           = config.getInteger(Keys.VALHALLA_BUFFER_SIZE);
        this.maxSnapDistanceMetres = config.getInteger(Keys.VALHALLA_MAX_SNAP_DISTANCE);
        this.objectMapper         = new ObjectMapper();

        LOGGER.info(
                "ValhallaTraceAttributesProvider initialised — url={}, bufferSize={}, "
                + "searchRadius={}m, gpsAccuracy={}m, maxSnapDistance={}m",
                valhallaUrl, bufferSize, searchRadius, gpsAccuracy, maxSnapDistanceMetres);
    }


    public void bufferPoint(long deviceId, double latitude, double longitude, long unixTimeSec) {
        String key = String.valueOf(deviceId);
        deviceBuffers.compute(key, (k, buf) -> {
            if (buf == null) {
                buf = new ArrayList<>();
            }
            buf.add(new BufferedPoint(latitude, longitude, unixTimeSec));
            if (buf.size() > bufferSize) {
                buf.remove(0);
            }
            return buf;
        });
    }


    @Override
    public void getTollRoute(double latitude, double longitude, TollRouteProviderCallback callback) {
        for (Map.Entry<String, List<BufferedPoint>> entry : deviceBuffers.entrySet()) {
            List<BufferedPoint> buf = entry.getValue();
            if (!buf.isEmpty()) {
                BufferedPoint last = buf.get(buf.size() - 1);
                if (coordsMatch(last.lat, latitude) && coordsMatch(last.lon, longitude)) {
                    if (buf.size() < 2) {
                        LOGGER.debug("Valhalla: coord-lookup found buffer for {} but only {} point(s) — skipping",
                                entry.getKey(), buf.size());
                        callback.onSuccess(emptyTollData());
                        return;
                    }
                    dispatchRequest(toShapePoints(buf), callback);
                    return;
                }
            }
        }
        LOGGER.debug("Valhalla: no device buffer matched ({}, {}) — returning empty toll data", latitude, longitude);
        callback.onSuccess(emptyTollData());
    }


    public void getTollRouteForDevice(long deviceId, TollRouteProviderCallback callback) {
        String key = String.valueOf(deviceId);
        List<BufferedPoint> buf = deviceBuffers.get(key);

        if (buf == null || buf.size() < 2) {
            LOGGER.debug(
                    "Valhalla: buffer for deviceId={} has {} point(s) — need ≥2, skipping",
                    deviceId, buf == null ? 0 : buf.size());
            callback.onSuccess(emptyTollData());
            return;
        }

        List<ShapePoint> shape = toShapePoints(buf);
        LOGGER.debug("Valhalla: dispatching {} buffered points for deviceId={}", shape.size(), deviceId);
        dispatchRequest(shape, callback);
    }


    private static boolean coordsMatch(double a, double b) {
        return Math.abs(a - b) < 1e-7;
    }

    private static List<ShapePoint> toShapePoints(List<BufferedPoint> buf) {
        List<ShapePoint> shape = new ArrayList<>(buf.size());
        for (BufferedPoint bp : buf) {
            shape.add(new ShapePoint(bp.lat, bp.lon, bp.unixTimeSec));
        }
        return shape;
    }

    private void dispatchRequest(List<ShapePoint> shape, TollRouteProviderCallback callback) {
        TraceRequest request = new TraceRequest(
                shape,
                new TraceOptions(searchRadius, gpsAccuracy)
        );

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            callback.onFailure(e);
            return;
        }

        LOGGER.debug("Valhalla trace_attributes request ({} points): {}", shape.size(), requestBody);

        client.target(valhallaUrl)
                .request(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "ridesiq-poc")
                .async()
                .post(Entity.json(requestBody), new InvocationCallback<String>() {

                    @Override
                    public void completed(String responseBody) {
                        try {
                            TraceResponse response = objectMapper.readValue(responseBody, TraceResponse.class);
                            TollData tollData = processResponse(response, shape);
                            LOGGER.debug("Valhalla result: toll={}, surface={}", tollData.getToll(),
                                    tollData.getSurface());
                            callback.onSuccess(tollData);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to parse Valhalla response: {}", e.getMessage());
                            callback.onFailure(e);
                        }
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        LOGGER.warn("Valhalla HTTP request failed: {}", throwable.getMessage());
                        callback.onFailure(throwable);
                    }
                });
    }

    public TollData processResponse(TraceResponse response, List<ShapePoint> shape) {
        if (response == null) {
            return emptyTollData();
        }

        List<ValhallaEdge>  edges   = response.edges;
        List<MatchedPoint>  matched = response.matchedPoints;

        if (edges == null || edges.isEmpty()) {
            LOGGER.debug("Valhalla returned no edges");
            return emptyTollData();
        }

        if (matched != null && !matched.isEmpty()) {
            int lastIdx = matched.size() - 1;
            MatchedPoint mp = matched.get(lastIdx);

            if (mp.distanceFromTracePoint != null
                    && mp.distanceFromTracePoint > maxSnapDistanceMetres) {
                LOGGER.debug(
                        "Last point snap distance {:.1f}m > threshold {}m — treating as unmatched",
                        mp.distanceFromTracePoint, maxSnapDistanceMetres);
                return emptyTollData();
            }

            if (mp.edgeIndex != null && mp.edgeIndex < edges.size()) {
                ValhallaEdge edge = edges.get(mp.edgeIndex);
                return edgeToTollData(edge);
            }
        }

        LOGGER.debug("matched_points unavailable — scanning all {} edges for toll flag", edges.size());
        for (ValhallaEdge edge : edges) {
            if (Boolean.TRUE.equals(edge.toll)) {
                return edgeToTollData(edge);
            }
        }

        ValhallaEdge first = edges.get(0);
        return new TollData(false, null, null, first.surface, first.roadClass, null);
    }

    private TollData edgeToTollData(ValhallaEdge edge) {
        return new TollData(
                Boolean.TRUE.equals(edge.toll),
                null,
                null,
                edge.surface,
                edge.roadClass,
                null
        );
    }

    private static TollData emptyTollData() {
        return new TollData(false, null, null, null, null, null);
    }
}

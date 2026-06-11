package org.traccar.tollroute.valhalla;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;


public final class ValhallaRequest {

    private ValhallaRequest() { }


    public static final class Body {
        @JsonProperty("shape")
        private final List<ShapePoint> shape;

        @JsonProperty("costing")
        private final String costing = "auto";

        @JsonProperty("shape_match")
        private final String shapeMatch = "map_snap";

        @JsonProperty("trace_options")
        private final TraceOptions traceOptions;

        @JsonProperty("filters")
        private final Filters filters = new Filters();

        public Body(List<ShapePoint> shape, TraceOptions traceOptions) {
            this.shape        = shape;
            this.traceOptions = traceOptions;
        }
    }


    public static final class ShapePoint {
        @JsonProperty("lat")  private final double lat;
        @JsonProperty("lon")  private final double lon;
        @JsonProperty("time") private final long   time;

        public ShapePoint(double lat, double lon, long time) {
            this.lat  = lat;
            this.lon  = lon;
            this.time = time;
        }
    }


    public static final class TraceOptions {
        @JsonProperty("search_radius") private final int searchRadius;
        @JsonProperty("gps_accuracy")  private final int gpsAccuracy;

        public TraceOptions(int searchRadius, int gpsAccuracy) {
            this.searchRadius = searchRadius;
            this.gpsAccuracy  = gpsAccuracy;
        }
    }


    public static final class Filters {
        @JsonProperty("attributes")
        private final List<String> attributes = List.of(
                "edge.way_id",
                "edge.toll",
                "edge.surface",
                "edge.road_class",
                "edge.speed_limit",
                "edge.names",
                "edge.begin_heading",
                "edge.end_heading",
                "matched.point",
                "matched.type",
                "matched.edge_index",
                "matched.distance_from_trace_point"
        );

        @JsonProperty("action")
        private final String action = "include";
    }
}

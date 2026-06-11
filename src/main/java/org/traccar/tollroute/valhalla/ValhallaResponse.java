package org.traccar.tollroute.valhalla;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;


public final class ValhallaResponse {

    private ValhallaResponse() { }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Body {
        @JsonProperty("edges")
        public List<Edge> edges;

        @JsonProperty("matched_points")
        public List<MatchedPoint> matchedPoints;
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Edge {
        @JsonProperty("way_id")
        public Long wayId;

        @JsonProperty("toll")
        public Boolean toll;

        @JsonProperty("surface")
        public String surface;

        @JsonProperty("road_class")
        public String roadClass;

        @JsonProperty("speed_limit")
        public Integer speedLimit;

        @JsonProperty("names")
        public List<String> names;

        @JsonProperty("begin_heading")
        public Double beginHeading;

        @JsonProperty("end_heading")
        public Double endHeading;

        @JsonProperty("begin_shape_index")
        public Integer beginShapeIndex;

        @JsonProperty("end_shape_index")
        public Integer endShapeIndex;
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MatchedPoint {
        @JsonProperty("lat")
        public double lat;

        @JsonProperty("lon")
        public double lon;

        @JsonProperty("type")
        public String type;

        @JsonProperty("edge_index")
        public Integer edgeIndex;

        @JsonProperty("distance_from_trace_point")
        public Double distanceFromTracePoint;
    }
}

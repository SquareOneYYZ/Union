package org.traccar.tollroute.valhalla;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class ValhallaResponse {

    private ValhallaResponse() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Body {

        @JsonProperty("edges")
        private List<Edge> edges;

        @JsonProperty("matched_points")
        private List<MatchedPoint> matchedPoints;

        public List<Edge> getEdges() {
            return edges;
        }

        public void setEdges(List<Edge> edges) {
            this.edges = edges;
        }

        public List<MatchedPoint> getMatchedPoints() {
            return matchedPoints;
        }

        public void setMatchedPoints(List<MatchedPoint> matchedPoints) {
            this.matchedPoints = matchedPoints;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Edge {

        @JsonProperty("way_id")
        private Long wayId;

        @JsonProperty("toll")
        private Boolean toll;

        @JsonProperty("surface")
        private String surface;

        @JsonProperty("road_class")
        private String roadClass;

        @JsonProperty("speed_limit")
        private Integer speedLimit;

        @JsonProperty("names")
        private List<String> names;

        @JsonProperty("begin_heading")
        private Double beginHeading;

        @JsonProperty("end_heading")
        private Double endHeading;

        @JsonProperty("begin_shape_index")
        private Integer beginShapeIndex;

        @JsonProperty("end_shape_index")
        private Integer endShapeIndex;

        public Long getWayId() {
            return wayId;
        }

        public void setWayId(Long wayId) {
            this.wayId = wayId;
        }

        public Boolean getToll() {
            return toll;
        }

        public void setToll(Boolean toll) {
            this.toll = toll;
        }

        public String getSurface() {
            return surface;
        }

        public void setSurface(String surface) {
            this.surface = surface;
        }

        public String getRoadClass() {
            return roadClass;
        }

        public void setRoadClass(String roadClass) {
            this.roadClass = roadClass;
        }

        public Integer getSpeedLimit() {
            return speedLimit;
        }

        public void setSpeedLimit(Integer speedLimit) {
            this.speedLimit = speedLimit;
        }

        public List<String> getNames() {
            return names;
        }

        public void setNames(List<String> names) {
            this.names = names;
        }

        public Double getBeginHeading() {
            return beginHeading;
        }

        public void setBeginHeading(Double beginHeading) {
            this.beginHeading = beginHeading;
        }

        public Double getEndHeading() {
            return endHeading;
        }

        public void setEndHeading(Double endHeading) {
            this.endHeading = endHeading;
        }

        public Integer getBeginShapeIndex() {
            return beginShapeIndex;
        }

        public void setBeginShapeIndex(Integer beginShapeIndex) {
            this.beginShapeIndex = beginShapeIndex;
        }

        public Integer getEndShapeIndex() {
            return endShapeIndex;
        }

        public void setEndShapeIndex(Integer endShapeIndex) {
            this.endShapeIndex = endShapeIndex;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MatchedPoint {

        @JsonProperty("lat")
        private double lat;

        @JsonProperty("lon")
        private double lon;

        @JsonProperty("type")
        private String type;

        @JsonProperty("edge_index")
        private Integer edgeIndex;

        @JsonProperty("distance_from_trace_point")
        private Double distanceFromTracePoint;

        public double getLat() {
            return lat;
        }

        public void setLat(double lat) {
            this.lat = lat;
        }

        public double getLon() {
            return lon;
        }

        public void setLon(double lon) {
            this.lon = lon;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Integer getEdgeIndex() {
            return edgeIndex;
        }

        public void setEdgeIndex(Integer edgeIndex) {
            this.edgeIndex = edgeIndex;
        }

        public Double getDistanceFromTracePoint() {
            return distanceFromTracePoint;
        }

        public void setDistanceFromTracePoint(Double distanceFromTracePoint) {
            this.distanceFromTracePoint = distanceFromTracePoint;
        }
    }
}

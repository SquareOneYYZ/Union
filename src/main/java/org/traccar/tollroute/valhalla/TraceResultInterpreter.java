package org.traccar.tollroute.valhalla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.tollroute.TollData;

import java.util.List;


public final class TraceResultInterpreter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceResultInterpreter.class);

    private final double              maxSnapDistanceM;
    private final BillableTollRegistry registry;

    public TraceResultInterpreter(double maxSnapDistanceM, BillableTollRegistry registry) {
        this.maxSnapDistanceM = maxSnapDistanceM;
        this.registry         = registry;
    }


    public TollData interpret(ValhallaResponse.Body response, List<ValhallaRequest.ShapePoint> shape) {
        if (response == null) {
            LOGGER.debug("TraceResultInterpreter: null response → toll=false");
            return empty();
        }

        List<ValhallaResponse.Edge>         edges   = response.edges;
        List<ValhallaResponse.MatchedPoint> matched = response.matchedPoints;

        if (edges == null || edges.isEmpty()) {
            LOGGER.debug("TraceResultInterpreter: no edges in response → toll=false");
            return empty();
        }

        if (matched == null || matched.isEmpty()) {

            LOGGER.debug("TraceResultInterpreter: no matched_points → toll=false (no fallback scan)");
            return empty();
        }

        int lastIdx = matched.size() - 1;
        ValhallaResponse.MatchedPoint mp = matched.get(lastIdx);

        if ("unmatched".equalsIgnoreCase(mp.type)) {
            LOGGER.debug("TraceResultInterpreter: last point type=unmatched → toll=false");
            return empty();
        }

        double snapDist = mp.distanceFromTracePoint != null ? mp.distanceFromTracePoint : 0.0;
        if (snapDist > maxSnapDistanceM) {
            LOGGER.debug("TraceResultInterpreter: snap distance {:.1f}m > threshold {:.0f}m → toll=false",
                    snapDist, maxSnapDistanceM);
            return empty();
        }

        if (mp.edgeIndex == null || mp.edgeIndex >= edges.size()) {
            LOGGER.debug("TraceResultInterpreter: edgeIndex={} out of range (edges={}) → toll=false",
                    mp.edgeIndex, edges.size());
            return empty();
        }

        ValhallaResponse.Edge edge = edges.get(mp.edgeIndex);

        if (!Boolean.TRUE.equals(edge.toll)) {
            LOGGER.debug("TraceResultInterpreter: edge {} toll=false → toll=false", edge.wayId);
            return new TollData(false, null, null, edge.surface, edge.roadClass, null);
        }

        if (!registry.isBillable(edge.names)) {
            LOGGER.debug("TraceResultInterpreter: edge {} toll=true but names {} not in whitelist → toll=false",
                    edge.wayId, edge.names);
            return new TollData(false, null, null, edge.surface, edge.roadClass, null);
        }

        String ref  = firstName(edge.names);
        String name = edge.names != null && edge.names.size() > 1 ? edge.names.get(1) : ref;
        LOGGER.debug("TraceResultInterpreter: toll=true way_id={} snap={:.1f}m ref={} surface={}",
                edge.wayId, snapDist, ref, edge.surface);
        return new TollData(true, ref, name, edge.surface, edge.roadClass, null);
    }

    private static String firstName(List<String> names) {
        return (names != null && !names.isEmpty()) ? names.get(0) : null;
    }

    private static TollData empty() {
        return new TollData(false, null, null, null, null, null);
    }
}

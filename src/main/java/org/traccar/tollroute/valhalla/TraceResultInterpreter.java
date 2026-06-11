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

        List<ValhallaResponse.Edge>         edges   = response.getEdges();
        List<ValhallaResponse.MatchedPoint> matched = response.getMatchedPoints();

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

        if ("unmatched".equalsIgnoreCase(mp.getType())) {
            LOGGER.debug("TraceResultInterpreter: last point type=unmatched → toll=false");
            return empty();
        }

        double snapDist = mp.getDistanceFromTracePoint() != null ? mp.getDistanceFromTracePoint() : 0.0;
        if (snapDist > maxSnapDistanceM) {
            LOGGER.debug("TraceResultInterpreter: snap distance {:.1f}m > threshold {:.0f}m → toll=false",
                    snapDist, maxSnapDistanceM);
            return empty();
        }

        if (mp.getEdgeIndex() == null || mp.getEdgeIndex() >= edges.size()) {
            LOGGER.debug("TraceResultInterpreter: edgeIndex={} out of range (edges={}) → toll=false",
                    mp.getEdgeIndex(), edges.size());
            return empty();
        }

        ValhallaResponse.Edge edge = edges.get(mp.getEdgeIndex());

        if (!Boolean.TRUE.equals(edge.getToll())) {
            LOGGER.debug("TraceResultInterpreter: edge {} toll=false → toll=false", edge.getWayId());
            return new TollData(false, null, null, edge.getSurface(), edge.getRoadClass(), null);
        }

        if (!registry.isBillable(edge.getNames())) {
            LOGGER.debug("TraceResultInterpreter: edge {} toll=true but names {} not in whitelist → toll=false",
                    edge.getWayId(), edge.getNames());
            return new TollData(false, null, null, edge.getSurface(), edge.getRoadClass(), null);
        }

        String ref  = firstName(edge.getNames());
        String name = edge.getNames() != null && edge.getNames().size() > 1 ? edge.getNames().get(1) : ref;
        LOGGER.debug("TraceResultInterpreter: toll=true way_id={} snap={:.1f}m ref={} surface={}",
                edge.getWayId(), snapDist, ref, edge.getSurface());
        return new TollData(true, ref, name, edge.getSurface(), edge.getRoadClass(), null);
    }

    private static String firstName(List<String> names) {
        return (names != null && !names.isEmpty()) ? names.get(0) : null;
    }

    private static TollData empty() {
        return new TollData(false, null, null, null, null, null);
    }
}

package org.traccar.tollroute.valhalla;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.tollroute.TollData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class TraceResultInterpreterTest {

    private TraceResultInterpreter openInterpreter;
    private TraceResultInterpreter whitelistInterpreter;

    private static final List<ValhallaRequest.ShapePoint> SHAPE = List.of(
            new ValhallaRequest.ShapePoint(43.0, -79.0, 1000L),
            new ValhallaRequest.ShapePoint(43.001, -79.001, 1008L)
    );

    @BeforeEach
    void setUp() {
        openInterpreter = new TraceResultInterpreter(
                30.0,
                new BillableTollRegistry(List.of()));

        whitelistInterpreter = new TraceResultInterpreter(
                30.0,
                new BillableTollRegistry(List.of("407 ETR")));
    }

    @Test
    void nullResponseReturnsFalse() {
        TollData td = openInterpreter.interpret(null, SHAPE);
        assertFalse(td.getToll());
    }

    @Test
    void emptyEdgesReturnsFalse() {
        ValhallaResponse.Body r = new ValhallaResponse.Body();
        r.setEdges(List.of());
        r.setMatchedPoints(List.of());

        assertFalse(openInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void noMatchedPointsReturnsFalse() {
        ValhallaResponse.Body r =
                response(tollEdge("407 ETR"), matchedPoint(0, 5.0, "matched"));

        r.setMatchedPoints(null);

        assertFalse(openInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void tollEdgeMatchedWithinSnapDistance() {
        ValhallaResponse.Body r =
                response(tollEdge("407 ETR"), matchedPoint(0, 3.2, "matched"));

        TollData td = openInterpreter.interpret(r, SHAPE);

        assertTrue(td.getToll());
        assertEquals("407 ETR", td.getRef());
        assertEquals("paved_smooth", td.getSurface());
    }

    @Test
    void nonTollEdgeReturnsFalse() {
        ValhallaResponse.Body r =
                response(nonTollEdge(), matchedPoint(0, 3.2, "matched"));

        assertFalse(openInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void unmatchedLastPointReturnsFalse() {
        ValhallaResponse.Body r =
                response(tollEdge("407 ETR"), matchedPoint(0, 3.2, "unmatched"));

        assertFalse(openInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void interpolatedTypeIsAccepted() {
        ValhallaResponse.Body r =
                response(tollEdge("407 ETR"), matchedPoint(0, 3.2, "interpolated"));

        assertTrue(openInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void snapDistanceExceedsThresholdReturnsFalse() {
        ValhallaResponse.Body r =
                response(tollEdge("407 ETR"), matchedPoint(0, 31.0, "matched"));

        assertFalse(openInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void snapDistanceExactlyAtThresholdAccepted() {
        ValhallaResponse.Body r =
                response(tollEdge("407 ETR"), matchedPoint(0, 30.0, "matched"));

        assertTrue(openInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void tollEdgeNotInWhitelistReturnsFalse() {
        ValhallaResponse.Body r =
                response(tollEdge("Scenic Park Road"),
                        matchedPoint(0, 3.2, "matched"));

        assertFalse(
                whitelistInterpreter.interpret(r, SHAPE).getToll(),
                "Park road with toll=yes but not in whitelist must return toll=false");
    }

    @Test
    void tollEdgeInWhitelistReturnsTrue() {
        ValhallaResponse.Body r =
                response(tollEdge("407 ETR"),
                        matchedPoint(0, 3.2, "matched"));

        assertTrue(whitelistInterpreter.interpret(r, SHAPE).getToll());
    }

    @Test
    void openModeAcceptsAnyTollEdge() {
        ValhallaResponse.Body r =
                response(tollEdge("Some Random Private Toll"),
                        matchedPoint(0, 3.2, "matched"));

        assertTrue(
                openInterpreter.interpret(r, SHAPE).getToll(),
                "Open mode must accept any toll=yes edge");
    }

    private static ValhallaResponse.Body response(
            ValhallaResponse.Edge edge,
            ValhallaResponse.MatchedPoint mp) {

        ValhallaResponse.Body r = new ValhallaResponse.Body();
        r.setEdges(List.of(edge));
        r.setMatchedPoints(List.of(mp));

        return r;
    }

    private static ValhallaResponse.Edge tollEdge(String name) {
        ValhallaResponse.Edge e = new ValhallaResponse.Edge();

        e.setToll(true);
        e.setSurface("paved_smooth");
        e.setRoadClass("motorway");
        e.setWayId(12345L);
        e.setNames(List.of(name));

        return e;
    }

    private static ValhallaResponse.Edge nonTollEdge() {
        ValhallaResponse.Edge e = new ValhallaResponse.Edge();

        e.setToll(false);
        e.setSurface("paved_smooth");
        e.setRoadClass("secondary");
        e.setWayId(99999L);
        e.setNames(List.of("Some Street"));

        return e;
    }

    private static ValhallaResponse.MatchedPoint matchedPoint(
            int edgeIndex,
            double snapDist,
            String type) {

        ValhallaResponse.MatchedPoint mp = new ValhallaResponse.MatchedPoint();

        mp.setEdgeIndex(edgeIndex);
        mp.setDistanceFromTracePoint(snapDist);
        mp.setType(type);

        return mp;
    }
}
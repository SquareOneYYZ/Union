package org.traccar.handler.toll;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two recorded routes replayed through the real gate.
 *
 * <p><b>What these fixtures do and do not contribute.</b> The injector scripts carry timestamps,
 * coordinates, speed and course - no enrichment. So the geometry and cadence are real and the
 * toll oracle is synthetic. That is the right split: the gate consumes only geometry, and it is
 * the gate's interaction with real reporting patterns that is under test here. Nothing in this
 * class depends on what a live Overpass instance would answer, and nothing here reaches the
 * network.
 */
public class TollRouteReplayTest {

    private static final int MINIMAL_DURATION = 6;
    private static final long DEVICE_ID = 5964L;

    private static TollChainHarness harness() {
        return new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "I-88", "Illinois Tollway"));
    }

    // ---------------------------------------------------------------- Ontario, the broken class

    /**
     * The second independent case for the broken class. Before this fixture the diagnosis rested
     * on a single 44-position drive; this is 284 positions on a different route, recorded a
     * different day, at the same profile class - 4.0 s median gap, 135 km/h, 145 m median step
     * between distinct coordinates.
     *
     * <p>The number that decides the outcome is not the pass rate but the <b>longest run of
     * consecutive passes</b>, because an enter needs {@code minimalDuration} of them in a row.
     * Here it is 1. Not one pair of adjacent positions both clears the gate, anywhere in the
     * route.
     */
    @Test
    public void ontarioRouteNeverProducesTwoConsecutiveLookups() {
        RouteFixture route = RouteFixture.load(RouteFixture.ONTARIO, DEVICE_ID);

        List<Integer> runs = route.passRunLengths(RouteFixture.GATE_METRES);
        int passes = runs.stream().mapToInt(Integer::intValue).sum();

        assertEquals(284, route.size());
        assertEquals(52, passes, "the gate must admit 52 of 284 positions");
        assertEquals(18, Math.round(100.0f * passes / route.size()), "about 18 percent");
        assertEquals(1, runs.stream().max(Integer::compareTo).orElseThrow(),
                "the longest run of consecutive passes must be 1 - the feature cannot work here");
        assertEquals(0, runs.stream().filter(run -> run >= MINIMAL_DURATION).count(),
                "and no run is long enough to confirm anything");
    }

    /**
     * The replay itself. The gate's own arithmetic decides which positions are looked up, so the
     * expectation is derived rather than hardcoded and stays meaningful if the gate value moves.
     *
     * <p><b>Red on master</b> - no enter fires, because the 232 gated-out positions each inject a
     * fabricated {@code false}. Green after 1a+1b, where the window sees 52 real confirmations
     * and needs only 6.
     */
    @Test
    public void ontarioRouteConfirmsAnEnterOnceUnknownsStopCountingAsFalse() {
        RouteFixture route = RouteFixture.load(RouteFixture.ONTARIO, DEVICE_ID);
        boolean[] expectedPasses = route.gatePasses(RouteFixture.GATE_METRES);

        TollChainHarness harness = harness();
        harness.acceptAll(route.positions());

        int expectedLookups = 0;
        for (boolean pass : expectedPasses) {
            if (pass) {
                expectedLookups++;
            }
        }
        assertEquals(expectedLookups, harness.lookupCount(),
                "the replayed gate must admit exactly the positions the arithmetic predicts");

        for (int i = 0; i < route.size(); i++) {
            Position position = route.positions().get(i);
            assertEquals(expectedPasses[i], position.hasAttribute(Position.KEY_TOLL),
                    "position " + (i + 1) + " enrichment must match the derived gate outcome");
        }

        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "52 real confirmations on a toll road must produce one enter");
    }

    // --------------------------------------------------------------- Illinois, both regimes

    /**
     * The property that makes this the primary fixture: the route is <b>bimodal</b>, and pass
     * rate alone hides it. 47 % of positions clear the gate, which would suggest alternating
     * pass and skip; the run-length distribution says otherwise - 1, 2, 3, ... 15, 24, 34.
     *
     * <p>One device passes through both regimes: long highway stretches where every position
     * clears 500 m and detection works normally, and dense stretches where it cannot. That is
     * what lets a single replay assert the gate is a no-op on the long runs and fatal on the
     * short ones.
     */
    @Test
    public void illinoisRouteContainsBothWorkingAndBrokenStretches() {
        RouteFixture route = RouteFixture.load(RouteFixture.ILLINOIS, DEVICE_ID);
        List<Integer> runs = route.passRunLengths(RouteFixture.GATE_METRES);

        assertEquals(1000, route.size());
        assertEquals(467, runs.stream().mapToInt(Integer::intValue).sum(),
                "467 of 1000 positions clear the gate");
        assertEquals(34, runs.stream().max(Integer::compareTo).orElseThrow(),
                "the longest run of consecutive passes");
        assertEquals(19, runs.stream().filter(run -> run >= MINIMAL_DURATION).count(),
                "19 runs are long enough to confirm an event on their own");
        assertTrue(runs.stream().anyMatch(run -> run == 1),
                "and the route must also contain runs of 1, or it is not bimodal");
    }

    /**
     * The broken regime, isolated - and the sharpest single statement of what 1a and 1b change.
     *
     * <p>Take the longest stretch of the route containing no run of {@code minimalDuration}
     * consecutive gate passes, and replay only that. On master it cannot confirm: every skipped
     * position injects a fabricated {@code false}, and without six passes in a row the window is
     * never homogeneous. After the fix it confirms, because the window counts <em>lookups</em>
     * rather than positions, and this stretch contains far more than six of them - they are
     * simply not adjacent.
     *
     * <p>An earlier version of this test asserted the opposite, that a dense stretch confirms
     * nothing on any branch. That was a wrong premise rather than a defect: it silently assumed
     * the post-fix window still required consecutive positions, which is exactly the assumption
     * 1b removes. Recorded here because the mistake is an easy one to repeat.
     */
    @Test
    public void aDenseStretchConfirmsOnlyOnceUnknownsStopCountingAsFalse() {
        RouteFixture route = RouteFixture.load(RouteFixture.ILLINOIS, DEVICE_ID);
        boolean[] passes = route.gatePasses(RouteFixture.GATE_METRES);

        int start = -1;
        int length = 0;
        int currentStart = 0;
        int run = 0;
        for (int i = 0; i < passes.length; i++) {
            run = passes[i] ? run + 1 : 0;
            if (run >= MINIMAL_DURATION) {
                currentStart = i + 1;
                run = 0;
            } else if (i - currentStart + 1 > length) {
                length = i - currentStart + 1;
                start = currentStart;
            }
        }

        List<Position> stretch = route.positions().subList(start, start + length);
        TollChainHarness harness = harness();
        harness.acceptAll(stretch);

        assertTrue(length >= 50, "the dense stretch must be substantial; found " + length);

        List<Integer> stretchRuns = new java.util.ArrayList<>();
        int consecutive = 0;
        for (int i = start; i < start + length; i++) {
            if (passes[i]) {
                consecutive++;
            } else if (consecutive > 0) {
                stretchRuns.add(consecutive);
                consecutive = 0;
            }
        }
        if (consecutive > 0) {
            stretchRuns.add(consecutive);
        }
        assertTrue(stretchRuns.stream().allMatch(r -> r < MINIMAL_DURATION),
                "by construction no run in this stretch reaches six consecutive passes");
        assertTrue(harness.lookupCount() >= MINIMAL_DURATION,
                "yet it holds well over six real lookups - " + harness.lookupCount()
                        + " - just never six in a row");

        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "six non-adjacent lookups are still six confirmations, so the enter must fire");
    }

    /**
     * The whole route, end to end. Both regimes, one replay, against an oracle that answers
     * "on a toll road" everywhere - so the correct output is one enter and no exit at all.
     *
     * <p><b>Red on master, and instructive in how.</b> Master does not produce zero events here;
     * it produces <b>two</b> enters. The long runs confirm, then a dense stretch fills the window
     * with fabricated falses and fires an exit, then a later long run confirms again. The gate
     * does not merely suppress events on a bimodal route - it manufactures spurious
     * enter/exit churn, splitting one traversal into several.
     *
     * <p>That churn is the mechanism behind errata item 20's observation that the surplus of
     * exits over enters more than doubled after the gate deployed, from 0.79 % to 1.94 %.
     *
     * <p>One coincidence worth not over-reading: injecting this route against staging reportedly
     * produced two toll events as well. That is a different mechanism - two real toll facilities
     * along the route, against this replay's single synthetic always-toll oracle - and the
     * matching count is chance, not corroboration.
     */
    @Test
    public void illinoisRouteConfirmsOnceAndDoesNotChurn() {
        RouteFixture route = RouteFixture.load(RouteFixture.ILLINOIS, DEVICE_ID);
        TollChainHarness harness = harness();
        harness.acceptAll(route.positions());

        assertEquals(467, harness.lookupCount(),
                "the replayed gate must admit the same 467 positions the arithmetic predicts");
        assertEquals(0, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_EXIT).size(),
                "the vehicle never leaves the toll road, so no exit may be emitted");
        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "one continuous toll road, one enter - master splits it into two");
    }

    // ------------------------------------------------------------------------ shared properties

    /**
     * Duplicate positions are present in both routes and in the field export. Recorded here as
     * evidence for errata item 28; what a duplicate actually does to the chain is
     * {@code DuplicatePositionFilterTest}, because it is a {@code FilterHandler} fact.
     */
    @Test
    public void bothRoutesCarryDuplicatePositions() {
        assertEquals(14, RouteFixture.load(RouteFixture.ONTARIO, DEVICE_ID).duplicatePairs(),
                "14 adjacent pairs share a timestamp and a coordinate");
        assertEquals(10, RouteFixture.load(RouteFixture.ILLINOIS, DEVICE_ID).duplicatePairs(),
                "10 in the 1000-point route");
    }

    /**
     * A duplicate never clears the gate, on any route, because it has moved zero metres. So it
     * costs no lookup - and on master it still cost a window slot, which is the point of the
     * filter test next door.
     */
    @Test
    public void duplicatePositionsNeverClearTheGate() {
        for (String resource : List.of(RouteFixture.ONTARIO, RouteFixture.ILLINOIS)) {
            RouteFixture route = RouteFixture.load(resource, DEVICE_ID);
            boolean[] passes = route.gatePasses(RouteFixture.GATE_METRES);
            List<Position> positions = route.positions();
            for (int i = 1; i < positions.size(); i++) {
                boolean duplicate = positions.get(i).getFixTime().equals(positions.get(i - 1).getFixTime())
                        && positions.get(i).getLatitude() == positions.get(i - 1).getLatitude()
                        && positions.get(i).getLongitude() == positions.get(i - 1).getLongitude();
                if (duplicate) {
                    assertFalse(passes[i], resource + " position " + (i + 1)
                            + " is a duplicate and must not clear the gate");
                }
            }
        }
    }
}

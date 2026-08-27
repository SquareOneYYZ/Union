package org.traccar.handler.toll;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.state.TollRouteProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The non-regression guard for the devices that work today.
 *
 * <p>Roughly a thousand production devices emit toll events under the current code. They do so
 * because their reporting interval is long enough that every position clears the 500 m gate, so
 * nothing is ever skipped. The tri-state must be a no-op for them: no skipped position means no
 * unknown reading, so the window sees exactly the booleans it saw before.
 *
 * <p>"By construction" is the argument, not the evidence. These tests are the evidence.
 */
public class TollWorkingDeviceNoOpTest {

    private static final int MINIMAL_DURATION = 6;
    private static final long DEVICE_ID = 5964L;

    /** 100 km/h at 30 s is 833 m per fix - every position clears a 500 m gate. */
    private static final double WORKING_SPEED_KPH = 100.0;
    private static final double WORKING_CADENCE_S = 30.0;

    private static List<Position> workingProfile(int count) {
        return SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, WORKING_SPEED_KPH, WORKING_CADENCE_S, count);
    }

    private static TollChainHarness harness() {
        return new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));
    }

    /**
     * The premise. If this fails, every other assertion in this class is measuring the wrong
     * thing - the profile would no longer be a working-device profile.
     *
     * <p>Green on master and after every stage 1 commit.
     */
    @Test
    public void theProfileIsOneWhereNothingIsEverSkipped() {
        List<Position> stream = workingProfile(12);
        TollChainHarness harness = harness();
        harness.acceptAll(stream);

        assertEquals(stream.size(), harness.lookupCount(),
                "833 m per fix must clear a 500 m gate on every position");
        for (Position position : stream) {
            assertFalse(position.getBoolean("tollLookupSkipped"),
                    "nothing is gated out on this profile, so no position may carry the skip marker");
            assertTrue(position.hasAttribute(Position.KEY_TOLL),
                    "every position must carry a real reading, so readToll can never return null");
        }
    }

    /**
     * The no-op itself, as a differential rather than an assertion about absolute values.
     *
     * <p>Two runs of the same journey. The first is the working profile. The second is the same
     * positions - same ids, same times, same coordinates - with one extra position interleaved
     * between each pair, 417 m along, close enough that the gate skips every one of them. The
     * interleaved stream therefore contains strictly more information and no less.
     *
     * <p>Adding positions the gate skips must not change the outcome. The two runs must agree on
     * the number of events, their types, their {@code eventTime}, their {@code positionId} and
     * their {@code tollDistance}.
     *
     * <p>This is what makes the claim provable inside one branch, without needing to run master
     * and the fix side by side: 1c moves {@code eventTime} in <em>both</em> arms equally, so the
     * comparison stays valid across the whole stack.
     *
     * <p><b>Red on master</b>, where the interleaved positions inject a fabricated {@code false}
     * into the window and the second run produces no events at all.
     */
    @Test
    public void positionsTheGateSkipsDoNotChangeTheOutcome() {
        List<Position> sparse = workingProfile(12);
        TollChainHarness sparseRun = harness();
        sparseRun.acceptAll(sparse);

        TollChainHarness denseRun = harness();
        denseRun.acceptAll(interleaveSkippablePositions(workingProfile(12)));

        List<Event> expected = sparseRun.events();
        List<Event> actual = denseRun.events();

        assertEquals(describe(expected), describe(actual),
                "interleaving gated-out positions must leave every emitted event identical");
        assertEquals(1, expected.size(), "the journey itself must produce exactly one enter");
    }

    /**
     * The same claim stated over the stream position at which the decision is taken. The enter
     * must confirm on the sixth reading, which is the sixth position on this profile - not the
     * seventh, and not never.
     *
     * <p>Green on master and after every stage 1 commit: this is the property 1a and 1b must
     * not move.
     */
    @Test
    public void theEnterStillConfirmsOnTheSixthReading() {
        List<Position> stream = workingProfile(12);
        TollChainHarness harness = harness();

        List<Integer> firedAt = new ArrayList<>();
        for (int i = 0; i < stream.size(); i++) {
            int before = harness.events().size();
            harness.accept(stream.get(i));
            if (harness.events().size() > before) {
                firedAt.add(i + 1);
            }
        }

        assertEquals(List.of(MINIMAL_DURATION), firedAt,
                "one event, confirmed while processing the sixth position");
        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size());
    }

    /**
     * 1c, kept deliberately separate so the two claims cannot be confused. For this same working
     * device the recorded position is <em>expected</em> to move earlier - from the sixth reading
     * to the first, 4,167 m back at this cadence. That is a behaviour change for devices that
     * already work, not a no-op, and it is the one thing in stage 1 that needs a decision rather
     * than a review.
     *
     * <p>Red on master and after 1a+1b; green after 1c.
     */
    @Test
    public void backdatingDeliberatelyMovesTheRecordedEnterForTheSameDevice() {
        List<Position> stream = workingProfile(12);
        TollChainHarness harness = harness();
        harness.acceptAll(stream);

        Event enter = harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).get(0);
        Position first = stream.get(0);
        Position confirming = stream.get(MINIMAL_DURATION - 1);

        assertEquals(first.getDeviceTime(), enter.getEventTime(),
                "the enter must carry the first confirming reading");
        assertEquals(first.getId(), enter.getPositionId());
        assertNotEquals(confirming.getId(), enter.getPositionId(),
                "and must no longer carry the position that closed the window");
        // Literals, not TollRouteProcessor.SCHEMA_VERSION: this class must compile on the
        // stage 0 branch too, where that constant does not exist yet.
        assertEquals(2, enter.getInteger("tollEventSchema"),
                "backdated events must be stamped so the discontinuity is queryable");
    }

    /**
     * The same no-op claim on real data rather than a generated profile.
     *
     * <p>The Illinois route's longest run is 34 consecutive gate passes. Replayed on its own that
     * stretch is a working-device profile - nothing is skipped inside it - so it must behave
     * exactly like the synthetic one. The synthetic profile isolates the claim with nothing else
     * varying; this shows it holds on a real cadence with real geometry.
     */
    @Test
    public void aLongRunOfRealPositionsBehavesLikeTheSyntheticProfile() {
        RouteFixture route = RouteFixture.load(RouteFixture.ILLINOIS, DEVICE_ID);
        int start = route.longestRunStart(RouteFixture.GATE_METRES);
        int length = route.passRunLengths(RouteFixture.GATE_METRES).stream()
                .max(Integer::compareTo).orElseThrow();

        List<Position> run = route.positions().subList(start, start + length);
        TollChainHarness harness = harness();
        harness.acceptAll(run);

        assertTrue(length >= MINIMAL_DURATION,
                "the route must contain a run long enough to confirm; found " + length);
        assertEquals(run.size(), harness.lookupCount(),
                "every position in a run of consecutive passes must be enriched");
        for (Position position : run) {
            assertFalse(position.getBoolean("tollLookupSkipped"),
                    "no position inside a pass run may be skipped");
        }
        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "a real 34-position run on a toll road must confirm exactly once");
    }

    /**
     * Copies each position and inserts, after it, one extra position roughly 417 m further along
     * - half the profile's 833 m step. Half is the largest fraction that guarantees the extra is
     * skipped while the following real position still clears the gate from the same reference
     * point, so the real stream's pass pattern is untouched.
     *
     * <p>The extras get ids from a separate range; the real positions keep theirs, which is what
     * makes the {@code positionId} comparison meaningful.
     */
    private static List<Position> interleaveSkippablePositions(List<Position> stream) {
        List<Position> fine = SyntheticDrive.straightLine(DEVICE_ID, 43.65, -79.71, 45.0,
                WORKING_SPEED_KPH, WORKING_CADENCE_S / 2, stream.size() * 2);
        List<Position> dense = new ArrayList<>();
        for (int i = 0; i < stream.size(); i++) {
            dense.add(stream.get(i));
            int extraIndex = 2 * i + 1;
            if (extraIndex < fine.size()) {
                Position extra = fine.get(extraIndex);
                extra.setId(800_000_000L + extraIndex);
                dense.add(extra);
            }
        }
        return dense;
    }

    /** Events reduced to the fields the no-op claim is about, so a mismatch reads clearly. */
    private static List<String> describe(List<Event> events) {
        List<String> described = new ArrayList<>();
        for (Event event : events) {
            described.add(String.format("%s eventTime=%s positionId=%d tollDistance=%.1f",
                    event.getType(), event.getEventTime(), event.getPositionId(),
                    event.getDouble(TollRouteProcessor.ATTRIBUTE_TOLL_DIST)));
        }
        return described;
    }
}

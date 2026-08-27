package org.traccar.handler.toll;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A secondary defect, found while implementing 1c: the event type was inferred from the
 * device's odometer rather than stated by the caller.
 *
 * <p>{@code TollRouteProcessor:28} called {@code checkEvent(state, position, 0, currentTotalDist)}
 * on the enter path, and {@code checkEvent} emitted an enter only when that fourth argument was
 * positive, an exit when it was zero. A device confirming a toll road while {@code totalDistance}
 * was still exactly 0 therefore emitted an exit where an enter belonged.
 *
 * <p>The mechanism is real but rare - errata item 20 measured 0 of 83 exits in the feature's
 * first month and 20 of 118,184 fleet-wide, which falsified it as the explanation for the exit
 * surplus. It is fixed here because 1c replaces the completing position's distance with the run
 * start's, so an inference on that value would start misfiring more often, not less.
 */
public class TollEventTypeTest {

    private static final int MINIMAL_DURATION = 6;
    private static final long DEVICE_ID = 5964L;

    @Test
    public void deviceAtOdometerZeroNeverEmitsAnExit() {
        TollChainHarness harness = driveAtOdometerZero(10);

        assertEquals(0, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_EXIT).size(),
                "an exit must not be emitted for a traversal that never started");
        assertTrue(harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size() >= 1,
                "confirming a toll road is an entry whatever the odometer reads");
    }

    /**
     * The type invariant, over the repeats.
     *
     * <p><b>Known gap, deliberately not fixed here.</b> The count is still wrong: this drive
     * emits one enter per toll-confirmed position after the window fills, not one per traversal.
     * The cause is a second use of the same collision - {@code tollStartDistance == 0} is both a
     * legal odometer reading and the sentinel for "not currently in a traversal"
     * ({@code TollRouteProcessor:26,29,37}). {@code stateStartToll} writes the traversal's start
     * distance into it, so a traversal beginning at zero leaves the sentinel unset and the
     * "entered toll" branch re-arms on every subsequent position.
     *
     * <p>Fixing that means giving the state an explicit in-traversal flag, and
     * {@code tollStartDistance} round-trips through the device row via
     * {@code TollRouteState.fromDevice/toDevice} and {@code Device.getTollStartDistance}. That
     * is a persisted-state semantic change with its own rollback story and it does not belong
     * inside stage 1. What is asserted here is only what this commit guarantees, and it stays
     * true once the count is fixed too.
     */
    @Test
    public void everyEventFromAnOdometerZeroTraversalIsAnEnter() {
        TollChainHarness harness = driveAtOdometerZero(20);

        assertTrue(harness.events().size() >= 1);
        assertTrue(harness.events().stream()
                        .allMatch(event -> Event.TYPE_DEVICE_TOLLROUTE_ENTER.equals(event.getType())),
                "whatever the count, none of them may be an exit");
    }

    private TollChainHarness driveAtOdometerZero(int count) {
        List<Position> stream = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, 100.0, 30.0, count);
        // A device that has genuinely accumulated no distance: new, or reset.
        stream.forEach(position -> position.set(Position.KEY_TOTAL_DISTANCE, 0.0));

        TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));
        harness.acceptAll(stream);
        return harness;
    }
}

package org.traccar.handler.toll;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stage 0.2, 0.3 and 0.4 - the tests that stop the defect returning under a different device
 * profile, pin the gate's distance semantics, and guard the population that works today.
 */
public class TollInvariantMatrixTest {

    private static final int MINIMAL_DURATION = 6;
    private static final long DEVICE_ID = 5964L;
    private static final double GATE_METRES = 500.0;

    /**
     * Stage 0.2 - the invariant matrix.
     *
     * <p>Every cell is a vehicle genuinely on a toll road for 9 km. An enter must fire in all
     * twenty. On master an enter fires only where {@code speed x cadence >= 500 m}, which is
     * four cells: 60 km/h at 30 s, 90 km/h at 30 s, 130 km/h at 15 s and 130 km/h at 30 s.
     * The other sixteen are silent - and the failure is worst at low speed, which the field
     * export does not cover at all.
     */
    @Test
    public void enterFiresAtEveryCadenceAndSpeed() {
        double[] cadences = {3.9, 5.0, 10.0, 15.0, 30.0};
        double[] speeds = {30.0, 60.0, 90.0, 130.0};
        List<String> silent = new ArrayList<>();

        for (double cadenceSeconds : cadences) {
            for (double speedKph : speeds) {
                double step = speedKph / 3.6 * cadenceSeconds;
                int count = (int) Math.ceil(9000.0 / step) + 2;

                TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                        TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));
                harness.acceptAll(SyntheticDrive.straightLine(
                        DEVICE_ID, 43.65, -79.71, 45.0, speedKph, cadenceSeconds, count));

                int enters = harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size();
                if (enters != 1) {
                    silent.add(String.format("%.0f km/h at %.1f s (%.0f m/fix): %d enters",
                            speedKph, cadenceSeconds, step, enters));
                }
            }
        }

        if (!silent.isEmpty()) {
            fail(String.format("%d of %d profiles on a toll road produced no single enter:%n  %s",
                    silent.size(), cadences.length * speeds.length, String.join("\n  ", silent)));
        }
    }

    /**
     * Stage 0.2, the other half - an exit must confirm too, at every profile. Nine kilometres
     * on the toll road, then nine off it.
     */
    @Test
    public void exitFiresAtEveryCadenceAndSpeed() {
        double[][] profiles = {{3.9, 30.0}, {3.9, 130.0}, {10.0, 60.0}, {30.0, 100.0}};
        List<String> wrong = new ArrayList<>();

        for (double[] profile : profiles) {
            double cadenceSeconds = profile[0];
            double speedKph = profile[1];
            double step = speedKph / 3.6 * cadenceSeconds;
            int count = (int) Math.ceil(9000.0 / step) + 2;

            List<Position> onToll = SyntheticDrive.straightLine(
                    DEVICE_ID, 43.65, -79.71, 45.0, speedKph, cadenceSeconds, count);
            List<Position> offToll = SyntheticDrive.continueFrom(onToll, speedKph, cadenceSeconds, count);

            // The oracle answers true only for coordinates in the first leg.
            List<String> tolled = onToll.stream().map(TollInvariantMatrixTest::key).toList();
            TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                    (latitude, longitude) -> tolled.contains(key(latitude, longitude))
                            ? new org.traccar.tollroute.TollData(true, "407 ETR", "407 Express Toll Route",
                                    null, null, null)
                            : new org.traccar.tollroute.TollData(false, null, null, null, null, null));

            harness.acceptAll(onToll);
            harness.acceptAll(offToll);

            int enters = harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size();
            int exits = harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_EXIT).size();
            if (enters != 1 || exits != 1) {
                wrong.add(String.format("%.0f km/h at %.1f s: %d enters, %d exits (want 1 and 1)",
                        speedKph, cadenceSeconds, enters, exits));
            }

            // Both edges must carry the schema stamp, not just the enter. It is set once in
            // checkEvent after the enter/exit branch, so this is the assertion that the shared
            // placement actually holds - without it, the exit half of the backdating
            // discontinuity would be unqueryable and nobody would find out from a green suite.
            for (Event event : harness.events()) {
                if (event.getInteger("tollEventSchema") != 2) {
                    wrong.add(String.format("%.0f km/h at %.1f s: %s carries tollEventSchema=%d",
                            speedKph, cadenceSeconds, event.getType(),
                            event.getInteger("tollEventSchema")));
                }
            }
        }

        if (!wrong.isEmpty()) {
            fail("a completed traversal must produce one enter and one exit:\n  "
                    + String.join("\n  ", wrong));
        }
    }

    /**
     * Stage 0.3 - a curved case that separates the two models of the gate.
     *
     * <p>On the field drive, straight-line displacement and summed path distance agree to
     * within 0.1 % on six of the eight lookup intervals, so the export cannot say which one
     * the gate measures. A circle can: on a 200 m radius the largest chord is 400 m, so a
     * straight-line gate can never fire again after the first lookup no matter how far the
     * vehicle drives, while an accumulated-distance gate fires every 500 m of arc.
     *
     * <p>Three laps is 3,770 m of path. The gate issues one lookup. That is the straight-line
     * model, and this test pins it so a future change to accumulated distance is a visible
     * decision rather than an accident.
     */
    @Test
    public void gateMeasuresStraightLineDisplacementNotPathDistance() {
        double radius = 200.0;
        double speedKph = 40.0;
        double cadenceSeconds = 2.0;
        double step = speedKph / 3.6 * cadenceSeconds;
        double turnPerFix = Math.toDegrees(step / radius);
        int laps = 3;
        int count = (int) Math.ceil(laps * 360.0 / turnPerFix);

        TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));
        List<Position> circuit = SyntheticDrive.curve(
                DEVICE_ID, 43.65, -79.71, 0.0, turnPerFix, speedKph, cadenceSeconds, count);
        harness.acceptAll(circuit);

        double pathDistance = step * (count - 1);
        assertTrue(pathDistance > 6 * GATE_METRES,
                "the circuit must be long enough that an accumulated-distance gate would fire six times");
        assertEquals(1, harness.lookupCount(),
                "a 400 m maximum chord means the straight-line gate fires once and never again");
        assertEquals(0, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "one lookup cannot fill a six-slot window, however far the vehicle drives");
    }

    /**
     * Stage 0.4 - the guard for the population that works today.
     *
     * <p>30 s at 100 km/h is 833 m per fix, so every position clears the 500 m gate and nothing
     * is ever gated out. The tri-state must be a no-op here by construction: no skipped
     * position means no unknown, so the window sees the same six booleans it sees on master and
     * confirms on the same reading.
     *
     * <p>This is the assertion for 1a and 1b. Backdating is asserted separately below, because
     * for this device it is a deliberate change rather than a no-op.
     */
    @Test
    public void slowCadenceDeviceIsUnaffectedByTheTriState() {
        double speedKph = 100.0;
        double cadenceSeconds = 30.0;
        List<Position> stream = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, speedKph, cadenceSeconds, 12);

        TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));

        List<Integer> firedAt = new ArrayList<>();
        for (int i = 0; i < stream.size(); i++) {
            int before = harness.events().size();
            harness.accept(stream.get(i));
            if (harness.events().size() > before) {
                firedAt.add(i + 1);
            }
        }

        assertEquals(stream.size(), harness.lookupCount(),
                "833 m per fix clears a 500 m gate every time - nothing may be skipped");
        for (Position position : stream) {
            assertFalse(position.getBoolean("tollLookupSkipped"),
                    "no position on this profile is gated out, so none may carry the skip marker");
            assertTrue(position.hasAttribute(Position.KEY_TOLL),
                    "every position on this profile must carry a real reading");
        }
        assertEquals(List.of(MINIMAL_DURATION), firedAt,
                "the enter must still confirm on the sixth reading, exactly as on master");
        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size());
    }

    /**
     * Stage 0.4, the 1c half. For the same already-working device, backdating deliberately
     * moves the recorded enter earlier - from the sixth reading to the first. At 833 m per fix
     * that is 4,167 m earlier, and it is a behaviour change for roughly a thousand devices that
     * emit events today.
     */
    @Test
    public void backdatingMovesTheRecordedEnterEarlierForAWorkingDevice() {
        List<Position> stream = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, 100.0, 30.0, 12);

        TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));
        harness.acceptAll(stream);

        Event enter = harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).get(0);
        Position first = stream.get(0);
        Position confirming = stream.get(MINIMAL_DURATION - 1);

        assertEquals(first.getDeviceTime(), enter.getEventTime(),
                "the enter must carry the first confirming reading, not the sixth");
        assertEquals(first.getId(), enter.getPositionId());
        assertEquals(2, enter.getInteger("tollEventSchema"),
                "backdated events must be stamped so the discontinuity is queryable");
        assertTrue(confirming.getFixTime().after(enter.getEventTime()),
                "the recorded time must move earlier, by five fixes at this cadence");
    }

    /**
     * The gate skip must not be inferable only from absence. If
     * {@code processing.copyAttributes} is ever enabled with {@code isToll} in the list, a
     * gated-out position inherits the previous value and absence stops meaning "unknown".
     * The positive marker survives that, because {@code CopyAttributesHandler.java:42} only
     * copies into a position that does not already have the attribute.
     */
    @Test
    public void skipMarkerIsPresentOnEveryGatedOutPosition() {
        List<Position> stream = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, 30.0, 3.9, 40);

        TollChainHarness harness = new TollChainHarness(MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));
        harness.acceptAll(stream);

        int skipped = 0;
        for (Position position : stream) {
            boolean enrichedHere = position.hasAttribute(Position.KEY_TOLL);
            boolean marked = position.getBoolean("tollLookupSkipped");
            assertTrue(enrichedHere ^ marked,
                    "a position is either enriched or marked skipped, never both and never neither");
            if (marked) {
                skipped++;
            }
        }
        assertTrue(skipped > 0, "at 32.5 m per fix most positions must be gated out");
        assertEquals(stream.size() - harness.lookupCount(), skipped);
    }

    private static String key(Position position) {
        return key(position.getLatitude(), position.getLongitude());
    }

    private static String key(double latitude, double longitude) {
        return String.format(java.util.Locale.ROOT, "%.6f:%.6f", latitude, longitude);
    }
}

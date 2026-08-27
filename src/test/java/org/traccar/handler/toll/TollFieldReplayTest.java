package org.traccar.handler.toll;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.tollroute.TollData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 0.1 - the field replay.
 *
 * <p>Device 5964, 2026-08-25 01:44:43-01:47:32 UTC: 44 positions, 5.55 km of path, mean gap
 * 3.93 s, median speed 70.7 kn (131 km/h). The vehicle joins the 407 ETR at position 14 and
 * passes the exit gantry at position 31.
 *
 * <p>Production enriched exactly nine of those 44 positions, and the recorded {@code isToll}
 * run is {@code false} at 6, {@code true} at 14/18/22/25/28/31/35, {@code false} at 41.
 * Seven consecutive confirmations, and no {@code deviceTollRouteEnter} was ever emitted.
 */
public class TollFieldReplayTest {

    private static final int PRODUCTION_MINIMAL_DURATION = 6;
    private static final long DEVICE_ID = 5964L;

    /**
     * The gate's reference point 120 m before the capture window, on the reverse bearing from
     * position 1 to position 6. Any point 92-146 m back reproduces the export; 120 m is the
     * midpoint, leaving 26 m of margin below the gate at position 5 and 30 m above it at
     * position 6.
     */
    private static final double PRIME_LATITUDE = 43.637605;
    private static final double PRIME_LONGITUDE = -79.728733;

    private TollChainHarness harness(int minimalDuration, FieldDrive drive) {
        TollChainHarness harness = new TollChainHarness(minimalDuration, drive.oracle());
        harness.primeGate(DEVICE_ID, PRIME_LATITUDE, PRIME_LONGITUDE);
        return harness;
    }

    /**
     * Validates the model rather than the fix: the real gate, primed to production's phase,
     * selects exactly the nine positions production enriched. Passes before and after stage 1
     * - if it ever fails, the gate's distance semantics changed and every other assertion in
     * this class is measuring something else.
     */
    @Test
    public void gateSelectsExactlyTheNinePositionsProductionEnriched() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness harness = harness(PRODUCTION_MINIMAL_DURATION, drive);

        harness.acceptAll(drive.positions());

        assertEquals(List.of(6, 14, 18, 22, 25, 28, 31, 35, 41), drive.enrichedIndexes(),
                "the export itself must carry nine enriched rows");
        assertEquals(9, harness.lookupCount(),
                "the 500 m gate must issue nine lookups over the drive");
        assertEquals(List.of(6, 14, 18, 22, 25, 28, 31, 35, 41),
                harness.enrichedPositions().stream()
                        .map(p -> drive.positions().indexOf(p) + 1).toList(),
                "the replayed gate must select the same positions production did");
    }

    /**
     * The headline defect. Seven consecutive true readings at minimalDuration=6 must confirm.
     *
     * <p>RED on master: the 35 gated-out positions read as {@code false} through
     * {@code TollEventHandler.java:87} and never let six consecutive trues into the window.
     */
    @Test
    public void fieldDriveConfirmsExactlyOneEnter() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness harness = harness(PRODUCTION_MINIMAL_DURATION, drive);

        harness.acceptAll(drive.positions());

        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "one toll entry happened on this drive and exactly one enter must be emitted");
    }

    /**
     * Stage 1c. The window closes on position 31 - the exit gantry - but the traversal began
     * at position 14. Recording the enter at the completing position puts it 2,936 m into a
     * 5,545 m drive, which for a distance-billed road is the whole error.
     *
     * <p>RED on master twice over: no enter is emitted at all, and if one were it would carry
     * position 31.
     */
    @Test
    public void enterIsRecordedAtTheFirstConfirmingReadingNotTheSixth() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness harness = harness(PRODUCTION_MINIMAL_DURATION, drive);

        harness.acceptAll(drive.positions());

        List<Event> enters = harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER);
        assertEquals(1, enters.size());
        Event enter = enters.get(0);

        Position firstConfirming = drive.position(14);
        Position completing = drive.position(31);

        assertEquals(firstConfirming.getDeviceTime(), enter.getEventTime(),
                "enter must carry the fix time of the first true reading (01:45:28), not the sixth");
        assertEquals(firstConfirming.getId(), enter.getPositionId(),
                "enter must reference the position that opened the run");
        assertFalse(completing.getDeviceTime().equals(enter.getEventTime()),
                "01:46:46 is the exit gantry - the old behaviour, and 2,936 m late");
    }

    /**
     * A correction to the brief. The prompt asks this replay to assert "exactly one enter and
     * one exit after the fix". The exit is not reachable from this capture and no
     * implementation can make it so.
     *
     * <p>Confirming an exit needs {@code minimalDuration} consecutive non-toll lookups. At a
     * 500 m gate that is six lookups over roughly 3 km. The capture ends 1,601 m past the
     * gantry and contains exactly one non-toll lookup after it (position 41). Position 35 -
     * 515 m past the gantry - still reads {@code isToll=true}, which errata item 4 attributes
     * to a cache hit rather than to the vehicle still being on the road.
     *
     * <p>So the honest assertion is: no exit, and the state is left mid-traversal. Asserting
     * one exit here would only be satisfiable by shortening the window, which the brief
     * explicitly forbids.
     */
    @Test
    public void noExitIsConfirmableWithinTheCapture() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness harness = harness(PRODUCTION_MINIMAL_DURATION, drive);

        harness.acceptAll(drive.positions());

        assertEquals(0, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_EXIT).size(),
                "the capture holds one non-toll lookup after the gantry; six are needed");
    }

    /**
     * Stage 0.5, first edge case. Positions 10/11, 33/34 and 41/42 are duplicate pairs -
     * identical fixTime and totalDistance, {@code distance: 0}.
     *
     * <p>The duplicate always fails the gate (0 m moved), so it contributes no lookup. What
     * matters after stage 1a is that it also contributes no window entry: today it appends a
     * fabricated {@code false}, and the 41/42 pair does it immediately after the traversal.
     */
    @Test
    public void duplicatePositionsContributeNoLookupAndNoWindowEntry() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness harness = harness(PRODUCTION_MINIMAL_DURATION, drive);

        harness.acceptAll(drive.positions());

        for (int index : new int[]{11, 34, 42}) {
            Position duplicate = drive.position(index);
            assertFalse(duplicate.hasAttribute(Position.KEY_TOLL),
                    "duplicate at index " + index + " must not be enriched");
            assertTrue(duplicate.getBoolean("tollLookupSkipped"),
                    "duplicate at index " + index + " must be marked as skipped, not left silently absent");
        }
        assertEquals(9, harness.lookupCount());
    }

    /**
     * Stage 0.5, second edge case. An Overpass timeout in the middle of the traversal must not
     * read as "left the toll road".
     *
     * <p>Failing the lookup at position 25 leaves six confirmations (14, 18, 22, 28, 31, 35),
     * so the enter still fires - one lookup later than it otherwise would, but it fires. On
     * master the failure is indistinguishable from a {@code false} and from a gated-out
     * position alike.
     */
    @Test
    public void overpassFailureMidTraversalDoesNotBreakTheRun() {
        FieldDrive drive = FieldDrive.load();
        Position failAt = drive.position(25);
        TollChainHarness.TollOracle backing = drive.oracle();
        TollChainHarness harness = new TollChainHarness(PRODUCTION_MINIMAL_DURATION,
                (latitude, longitude) -> latitude == failAt.getLatitude() && longitude == failAt.getLongitude()
                        ? null : backing.lookup(latitude, longitude));
        harness.primeGate(DEVICE_ID, PRIME_LATITUDE, PRIME_LONGITUDE);

        harness.acceptAll(drive.positions());

        assertEquals(1, harness.failureCount(), "exactly one lookup must have failed");
        assertTrue(failAt.getBoolean("tollLookupFailed"),
                "a failed lookup must be marked, mirroring regionLookupFailed");
        assertFalse(failAt.hasAttribute(Position.KEY_TOLL),
                "a failed lookup must not write an isToll value");
        assertEquals(1, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "a single timeout must not cancel a confirmed traversal");
    }

    /**
     * Stage 0.5, third edge case. A vehicle parked on a toll road reports from one coordinate,
     * so every position after the first fails the gate. The traversal must confirm once and
     * then stay quiet - no repeated enters, and no exit while it sits there.
     */
    @Test
    public void vehicleParkedOnTollRoadEmitsOneEnterAndNoExit() {
        TollChainHarness harness = new TollChainHarness(PRODUCTION_MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));

        List<Position> stream = SyntheticDrive.straightLine(
                DEVICE_ID, 43.65, -79.71, 45.0, 130.0, 4.0, 20);
        harness.acceptAll(stream);
        int entersWhileMoving = harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size();

        List<Position> parked = SyntheticDrive.stationary(
                DEVICE_ID, stream.get(stream.size() - 1), 30);
        harness.acceptAll(parked);

        assertEquals(entersWhileMoving, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_ENTER).size(),
                "parking must not re-fire the enter");
        assertEquals(0, harness.eventsOfType(Event.TYPE_DEVICE_TOLLROUTE_EXIT).size(),
                "parking on a toll road is not an exit");
        for (Position position : parked) {
            assertTrue(position.getBoolean("tollLookupSkipped"),
                    "every stationary position is gated out and must say so");
        }
    }

    /**
     * Stage 0.5, fourth edge case. A device's first-ever position has no gate reference, so it
     * is always enriched. That must produce a real lookup and a real reading rather than an
     * unknown - and it must not, on its own, confirm anything.
     */
    @Test
    public void firstEverPositionIsEnrichedAndConfirmsNothing() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness harness = new TollChainHarness(PRODUCTION_MINIMAL_DURATION,
                TollChainHarness.constant(true, "407 ETR", "407 Express Toll Route"));

        Position first = drive.position(1);
        harness.accept(first);

        assertEquals(1, harness.lookupCount(), "no reference point means no gate");
        assertTrue(first.hasAttribute(Position.KEY_TOLL));
        assertFalse(first.getBoolean("tollLookupSkipped"));
        assertTrue(harness.events().isEmpty(), "one reading cannot fill a six-slot window");
    }

    /**
     * The tri-state itself, at the read site. A gated-out position must be distinguishable
     * from a position that was looked up and found not to be on a toll road.
     */
    @Test
    public void gatedOutPositionIsDistinguishableFromConfirmedNotTolled() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness harness = harness(PRODUCTION_MINIMAL_DURATION, drive);

        harness.acceptAll(drive.positions());

        Position lookedUpNotTolled = drive.position(6);
        assertTrue(lookedUpNotTolled.hasAttribute(Position.KEY_TOLL));
        assertFalse(lookedUpNotTolled.getBoolean(Position.KEY_TOLL));
        assertFalse(lookedUpNotTolled.getBoolean("tollLookupSkipped"));

        Position gatedOut = drive.position(7);
        assertFalse(gatedOut.hasAttribute(Position.KEY_TOLL));
        assertTrue(gatedOut.getBoolean("tollLookupSkipped"));
    }

    /**
     * The oracle must answer from the export, not invent values - otherwise every assertion
     * above is testing the fixture rather than the code.
     */
    @Test
    public void oracleAnswersFromTheExport() {
        FieldDrive drive = FieldDrive.load();
        TollChainHarness.TollOracle oracle = drive.oracle();

        Position onToll = drive.position(14);
        TollData data = oracle.lookup(onToll.getLatitude(), onToll.getLongitude());
        assertTrue(data.getToll());
        assertEquals("407 ETR", data.getRef());
        assertEquals("407 Express Toll Route", data.getName());

        Position gantry = drive.position(31);
        assertTrue(oracle.lookup(gantry.getLatitude(), gantry.getLongitude()).getToll());
        assertEquals("toll_gantry", oracle.lookup(gantry.getLatitude(), gantry.getLongitude()).getHighway());

        Position offToll = drive.position(41);
        assertFalse(oracle.lookup(offToll.getLatitude(), offToll.getLongitude()).getToll());

        assertNull(oracle.lookup(0.0, 0.0).getRef(), "unvisited coordinates carry no tags");
    }
}

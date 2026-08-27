package org.traccar.handler.toll;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.session.state.TollRouteState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1b at the unit level: what the confirmation window counts, and what it forgets.
 */
public class TollRouteStateWindowTest {

    private static final int DURATION = 6;

    /**
     * An unknown must neither fill the window nor reset it. Six real confirmations separated by
     * gated-out positions are still six confirmations.
     */
    @Test
    public void unknownReadingsDoNotEnterTheWindow() {
        TollRouteState state = new TollRouteState();

        // The field drive's shape: one real lookup every four positions.
        List<Boolean> stream = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            stream.add(true);
            stream.add(null);
            stream.add(null);
            stream.add(null);
        }
        stream.forEach(value -> state.addOnToll(value, DURATION));

        assertEquals(List.of(true, true, true, true, true, true), state.getTollWindow(),
                "the window must hold six real lookups, not a mix of readings and fabrications");
        assertEquals(Boolean.TRUE, state.isOnToll(DURATION));
    }

    /**
     * The alternative that looks simpler and is wrong: treating an unknown as {@code false}.
     * That is what {@code getBoolean} does today, and it is why the field drive's seven
     * consecutive confirmations never produced an event.
     */
    @Test
    public void treatingUnknownAsFalseNeverConfirms() {
        TollRouteState state = new TollRouteState();

        // Twelve real confirmations, each followed by the three fabricated falses the gate
        // produces at the field drive's cadence. The window never once reads as "on toll".
        for (int i = 0; i < 12; i++) {
            state.addOnToll(true, DURATION);
            assertNotEquals(Boolean.TRUE, state.isOnToll(DURATION),
                    "confirmation " + (i + 1) + " must not be reachable through fabricated falses");
            for (int skipped = 0; skipped < 3; skipped++) {
                state.addOnToll(false, DURATION);
                assertNotEquals(Boolean.TRUE, state.isOnToll(DURATION));
            }
        }
    }

    /**
     * The trim is {@code while}, not {@code if}. A single {@code if} removes at most one entry
     * per call, so a window restored longer than {@code duration} grows by one and shrinks by
     * one on every position and never converges - and {@code isOnToll} then returns null
     * forever, because its size test is an equality.
     *
     * <p>This is the state a device is left in if {@code event.tollRoute.minimalDuration} is
     * ever lowered, and it is why the brief forbids lowering it as a workaround.
     */
    @Test
    public void anOverlongRestoredWindowConvergesDownward() {
        TollRouteState state = new TollRouteState();
        state.setTollWindow(new ArrayList<>(List.of(true, true, true, true, true, true, true, true, true, true)));

        state.addOnToll(true, DURATION);

        assertEquals(DURATION, state.getTollWindow().size(),
                "a stored window of 10 must converge to 6 on the first position after the change");
        assertEquals(Boolean.TRUE, state.isOnToll(DURATION),
                "and must then be able to decide again");
    }

    /** A window shorter than the duration and entirely false still reads as "not on a toll road". */
    @Test
    public void shortAllFalseWindowStillDecides() {
        TollRouteState state = new TollRouteState();
        state.addOnToll(false, DURATION);
        state.addOnToll(false, DURATION);

        assertEquals(Boolean.FALSE, state.isOnToll(DURATION));
    }

    /** A mixed window is undecided, which is the correct answer while evidence disagrees. */
    @Test
    public void mixedWindowIsUndecided() {
        TollRouteState state = new TollRouteState();
        state.addOnToll(true, DURATION);
        state.addOnToll(false, DURATION);

        assertNull(state.isOnToll(DURATION));
    }

    /**
     * Stage 1c's rollback constraint. {@code TollEventHandler.java:49} builds a plain
     * {@code ObjectMapper}, so {@code FAIL_ON_UNKNOWN_PROPERTIES} is on. Once 1c writes
     * run-start fields into {@code toll:<deviceId>}, rolling the jar back makes the older class
     * throw on every read - caught, logged at WARN, and the state nulled, once per position.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} prevents it, but only if it is
     * already in the version being rolled back to. This test asserts the annotation is present,
     * so the ordering constraint is enforced by the build rather than by the release notes.
     */
    @Test
    public void stateToleratesUnknownPropertiesForRollback() {
        String futureSchema = "{\"id\":1,\"changed\":false,\"tollWindow\":[true],"
                + "\"customTollWindow\":[],\"tollStartDistance\":0.0,\"tollExitDistance\":0.0,"
                + "\"aFieldFromALaterRelease\":{\"positionId\":7}}";

        TollRouteState state = assertDoesNotThrow(
                () -> new ObjectMapper().readValue(futureSchema, TollRouteState.class),
                "an older jar must tolerate state written by a newer one");
        assertEquals(List.of(true), state.getTollWindow());
    }

    /** Round-trips through Jackson the way {@code TollEventHandler} persists it. */
    @Test
    public void stateSurvivesAJacksonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TollRouteState state = new TollRouteState();
        state.addOnToll(true, DURATION);
        state.addOnToll(true, DURATION);
        state.setTollRef("407 ETR");

        TollRouteState restored = mapper.readValue(mapper.writeValueAsString(state), TollRouteState.class);

        assertEquals(state.getTollWindow(), restored.getTollWindow());
        assertEquals("407 ETR", restored.getTollRef());
        assertTrue(restored.isChanged());
    }
}

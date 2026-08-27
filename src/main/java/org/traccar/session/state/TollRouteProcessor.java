package org.traccar.session.state;

import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.Date;

public final class TollRouteProcessor {

    public static final String ATTRIBUTE_TOLL_DIST = "tollDistance";

    /** Stamped on every emitted toll event so the backdating boundary is queryable. */
    public static final String SCHEMA_ATTRIBUTE = "tollEventSchema";

    /** 2 = enter and exit carry the traversal boundary rather than the confirming position. */
    public static final int SCHEMA_VERSION = 2;

    private TollRouteProcessor() {
    }

    public static void updateState(
            TollRouteState state, Position position,
            String tollRef, String tollName, int  minimalDuration) {

        state.setEvent(null);

        double currentTotalDist =  position.getDouble(Position.KEY_TOTAL_DISTANCE);
        double startTollDist = state.getTollStartDistance();
        Boolean isOnToll = state.isOnToll(minimalDuration);
        if (isOnToll != null) {
            if (isOnToll) {
                if (startTollDist == 0) {   //entered toll
                    // The traversal began where the run of confirmations began, not where the
                    // window happened to close. Falls back to the completing position when the
                    // mark is absent, which is the case for state restored from a payload
                    // written before this field existed.
                    TollRouteState.PositionMark start = state.getRunStart();
                    boolean backdated = start != null && start.getFixTime() != null;
                    stateStartToll(state,
                            backdated ? start.getTotalDistance() : currentTotalDist,
                            backdated ? start.getFixTime() : position.getFixTime(),
                            tollRef, tollName);
                    checkEvent(state, position, 0, true, backdated ? start : null);
                } else if (startTollDist > 0) { // set names for tolls
                    if (state.getTollRef() == null && tollRef != null) {
                        state.setTollRef(tollRef);
                    }
                    if (state.getTollName() == null && tollName != null) {
                        state.setTollName(tollName);
                    }
                }
            } else if (startTollDist > 0) { // exited toll
                // The traversal ended at the last confirmation, not at the position that
                // completed the window of non-confirmations - which is minimalDuration lookups
                // further on, and would inflate every billed distance by that much.
                TollRouteState.PositionMark end = state.getLastTrue();
                boolean backdated = end != null && end.getFixTime() != null;
                double exitTotalDist = backdated ? end.getTotalDistance() : currentTotalDist;
                double currentTollDist = exitTotalDist - startTollDist;
                if (state.getTollExitDistance() == -1) { // good exit (enter notif was sent)
                    state.setTollExitDistance(exitTotalDist);
                    state.setTollrouteTime(backdated ? end.getFixTime() : position.getFixTime());

                    checkEvent(state, position, currentTollDist, false, backdated ? end : null);
                    state.setTollStartDistance(0);
                    state.setTollrouteTime(null);
                } else if (state.getTollExitDistance() == 0) { // bad exit (no enter event)
                    state.setTollStartDistance(0);
                    state.setTollrouteTime(null);
                }
            }
        }
    }

    /**
     * Emits an enter or an exit.
     *
     * <p>The caller says which. It used to be inferred from the distance argument - enter when
     * {@code tollStart > 0}, exit when {@code tollStart == 0} - which made the event type a
     * function of the device's odometer. A device confirming a toll road while
     * {@code totalDistance} was still exactly 0 emitted an exit where an enter belonged, and
     * because {@code stateStartToll} then left {@code tollStartDistance} at 0 it repeated on
     * every subsequent toll position. That path is rare in the record - 0 of 2,048 exits in the
     * local snapshot carry {@code tollDistance = 0}, and 20 of 118,184 fleet-wide - but it is
     * real, and inferring a type from a measurement is what made it possible.
     *
     * <p>It also has to go before backdating lands: 1c replaces the completing position's
     * distance with the run start's, so an inference on that value would start misfiring
     * wherever a traversal began at odometer zero.
     */
    private static void checkEvent(TollRouteState state, Position position, double tollDist, boolean enter,
                                   TollRouteState.PositionMark mark) {
        if (state.getTollrouteTime() != null) {
            Event event;
            if (enter) {
                event = new Event(Event.TYPE_DEVICE_TOLLROUTE_ENTER, position);
                state.setTollExitDistance(-1);
            } else {
                event = new Event(Event.TYPE_DEVICE_TOLLROUTE_EXIT, position);
                event.set(ATTRIBUTE_TOLL_DIST, tollDist);
            }

            if (mark != null) {
                // Event(String, Position) reads getDeviceTime for eventTime and getId for
                // positionId; both are re-pointed at the boundary position. That position has
                // already been through DatabaseHandler - the last position handler, at
                // ProcessingHandler:89 - so its id is a real row.
                event.setEventTime(mark.getDeviceTime());
                event.setPositionId(mark.getPositionId());
            }
            // Marks the discontinuity so it is queryable rather than forensic. Without it,
            // reverting the jar leaves a band of backdated events with nothing marking either
            // edge, and reconciling the history means inferring the boundaries from the shape
            // of the data.
            event.set(SCHEMA_ATTRIBUTE, SCHEMA_VERSION);

            event.set(Position.KEY_TOLL_NAME, state.getTollName());
            if (state.getTollName() == null && state.getTollRef() != null) {
                event.set(Position.KEY_TOLL_NAME, state.getTollRef());
            }
            if (state.getTollName() == null && state.getTollRef() == null) {
                event.set(Position.KEY_TOLL_NAME, " ");
            }
            event.set(Position.KEY_TOLL_REF, state.getTollRef());
            state.setTollrouteTime(null);
            state.setEvent(event);
        }
    }

    private static void stateStartToll(TollRouteState state, double tollStartDistance, Date startTime,
                                       String tollRef, String tollName) {
        state.setTollStartDistance(tollStartDistance);
        state.setTollExitDistance(0);
        state.setTollrouteTime(startTime);
        state.setTollRef(tollRef);
        state.setTollName(tollName);

    }

}

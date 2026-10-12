package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.List;

public class SpeedCameraState {
    @JsonProperty
    private long id;

    @JsonProperty
    private List<Long> detectionWindow = new ArrayList<>();

    @JsonProperty
    private String lastHighway;

    @JsonProperty
    private long lastEmitTime;

    @JsonIgnore
    private Event event;

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedCameraState.class);

    /** Event payload key for the vehicle speed, same as OverspeedProcessor so the FE formatSpeed path applies. */
    private static final String ATTRIBUTE_SPEED = "speed";

    /**
     * Kept for one release as a km/h duplicate of {@link #ATTRIBUTE_SPEED} for external consumers
     * (plan decision 0.6); dropped the release after.
     */
    private static final String ATTRIBUTE_DEVICE_SPEED_KMH = "deviceSpeed";

    /**
     * @param speedKnots      vehicle speed in knots (Traccar's unit)
     * @param speedLimitKnots applicable limit in knots, already checked to be positive by the caller
     */
    public void addDetection(
            Position position, int windowSize, String highwayTag, double speedKnots, double speedLimitKnots) {
        LOGGER.debug("Adding detection at time {} to window (current size={})", position.getFixTime(),
                detectionWindow.size());
        detectionWindow.add(position.getFixTime().getTime());

        if (detectionWindow.size() > windowSize) {
            detectionWindow.remove(0);
            LOGGER.debug("Removed oldest detection to maintain window size (windowSize={})", windowSize);
        }

        if (detectionWindow.size() == windowSize) {
            long now = position.getFixTime().getTime();
            if (highwayTag != null
                    && highwayTag.equalsIgnoreCase(lastHighway)
                    && (now - lastEmitTime) < 60000) { // 60 sec lock
                LOGGER.debug("Skipping duplicate speed camera event for deviceId={} highway={}",
                        position.getDeviceId(), highwayTag);
                return;
            }
            // Only emit event if not previously emitted for this detection
            if (event == null) {
                event = new Event(Event.TYPE_SPEED_CAMERA, position);
                event.set(Position.KEY_HIGHWAY, highwayTag);
                event.set(ATTRIBUTE_SPEED, speedKnots);
                event.set(Position.KEY_SPEED_LIMIT, speedLimitKnots);
                event.set(ATTRIBUTE_DEVICE_SPEED_KMH, UnitsConverter.kphFromKnots(speedKnots));

                lastHighway = highwayTag;
                lastEmitTime = now;
                LOGGER.info("SpeedCamera event created for deviceId={} at {} highway='{}' speed={} kn limit={} kn",
                        position.getDeviceId(), position.getFixTime(), highwayTag, speedKnots, speedLimitKnots);
            } else {
                LOGGER.debug("SpeedCamera event already exists for deviceId={}", position.getDeviceId());
            }
        } else {
            LOGGER.debug("Detection window not full yet (current size={}, windowSize={})",
                    detectionWindow.size(), windowSize);

        }
    }

    public Event getEvent() {
        return event;
    }

    public void clearEvent() {
        this.event = null;
    }
}

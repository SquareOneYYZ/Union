package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.List;

public class SpeedCameraState {
    @JsonProperty
    private long id;

    @JsonProperty
    private List<Long> detectionWindow = new ArrayList<>();

    @JsonIgnore
    private Event event;

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeedCameraState.class);

    public void addDetection(Position position, int windowSize) {
        LOGGER.debug("Adding detection at time {} to window (current size={})", position.getFixTime(),
                detectionWindow.size());
        detectionWindow.add(position.getFixTime().getTime());

        if (detectionWindow.size() > windowSize) {
            detectionWindow.remove(0);
            LOGGER.debug("Removed oldest detection to maintain window size (windowSize={})", windowSize);
        }

        if (detectionWindow.size() == windowSize) {
            // Only emit event if not previously emitted for this detection
            if (event == null) {
                event = new Event(Event.TYPE_SPEED_CAMERA, position);
                event.set(Position.KEY_HIGHWAY, Event.TYPE_SPEED_CAMERA);
                LOGGER.info("SpeedCamera event created for deviceId={} at {}", position.getDeviceId(),
                        position.getFixTime());
            } else {
                LOGGER.debug("SpeedCamera event already exists for deviceId={}", position.getDeviceId());
            }
        } else {
            LOGGER.debug("Detection window not full yet (current size={}, windowSize={})", detectionWindow.size(), windowSize);

        }
    }

    public Event getEvent() {
        return event;
    }

    public void clearEvent() {
        this.event = null;
    }
}

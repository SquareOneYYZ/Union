package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Event;
import org.traccar.model.Position;


@JsonIgnoreProperties(ignoreUnknown = true)
public class SurfaceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(SurfaceState.class);

    public static final int WINDOW_SIZE = 4;
    public static final double THRESHOLD_PERCENT = 50.0;

    @JsonProperty
    private long id;

    @JsonProperty
    private ConfidenceWindow<String> surfaceWindow =
            new ConfidenceWindow<>(WINDOW_SIZE, THRESHOLD_PERCENT);

    @JsonProperty
    private String lastEmittedSurface;

    @JsonIgnore
    private Event event;


    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public ConfidenceWindow<String> getSurfaceWindow() {
        return surfaceWindow;
    }

    public void setSurfaceWindow(ConfidenceWindow<String> surfaceWindow) {
        this.surfaceWindow = surfaceWindow;
    }

    public String getLastEmittedSurface() {
        return lastEmittedSurface;
    }

    public void setLastEmittedSurface(String lastEmittedSurface) {
        this.lastEmittedSurface = lastEmittedSurface;
    }

    public Event getEvent() {
        return event;
    }
    public void addSurface(String surface, Position position) {
        if (surfaceWindow == null) {
            surfaceWindow = new ConfidenceWindow<>(WINDOW_SIZE, THRESHOLD_PERCENT);
        }

        surfaceWindow.add(surface);

        String dominant = surfaceWindow.getDominantValue();
        LOGGER.debug(
                "SurfaceWindow updated: added='{}', dominant='{}' (threshold={}%), window={}",
                surface, dominant, THRESHOLD_PERCENT, surfaceWindow.getWindow());

        if (dominant != null) {
            if (!dominant.equals(lastEmittedSurface)) {
                event = new Event(Event.TYPE_SURFACE_TYPE, position);
                event.set(Position.KEY_SURFACE, dominant);
                lastEmittedSurface = dominant;
                LOGGER.info("Surface event created: surface='{}' for deviceId={}", dominant,
                        position.getDeviceId());
            } else {
                event = null;
                LOGGER.debug("Surface unchanged ('{}'), no event emitted.", dominant);
            }
        } else {
            event = null;
        }
    }


}

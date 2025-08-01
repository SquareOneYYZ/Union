package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SurfaceState {
    private static final Logger LOGGER = LoggerFactory.getLogger(SurfaceState.class);

    @JsonProperty
    private long id;

    @JsonProperty
    private List<String> surfaceWindow = new ArrayList<>();

    @JsonIgnore
    private Event event;

    public Event getEvent() {
        return event;
    }

    public void addSurface(String surface, int duration, Position position) {
        if (surfaceWindow == null) {
            surfaceWindow = new ArrayList<>();
        }

        surfaceWindow.add(surface);
        LOGGER.info("SurfaceWindow added value: {}, current size: {}, values: {}", surface,
                surfaceWindow.size(), surfaceWindow);

        if (surfaceWindow.size() > duration) {
            String removed = surfaceWindow.remove(0);
            LOGGER.info("SurfaceWindow removed oldest value: {}, new size: {}, values: {}",
                    removed, surfaceWindow.size(), surfaceWindow);
        }

        if (surfaceWindow.size() == duration) {
            LOGGER.info("SurfaceWindow reached required size {} with values: {}", duration, surfaceWindow);

            Set<String> unique = new HashSet<>(surfaceWindow);
            if (unique.size() == 1) {
                String confirmedSurface = unique.iterator().next();
                LOGGER.info("SurfaceWindow reached required size {} with same value: {}", duration, confirmedSurface);
                event = new Event(Event.TYPE_SURFACE_TYPE, position);
                event.set(Position.KEY_SURFACE, confirmedSurface);
            }
        }
    }

}

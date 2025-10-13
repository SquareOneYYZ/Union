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

    @JsonProperty
    private String lastEmittedSurface;

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
        if (surfaceWindow.size() > duration) {
            String removed = surfaceWindow.remove(0);
        }

        if (surfaceWindow.size() == duration) {
            Set<String> unique = new HashSet<>(surfaceWindow);
            if (unique.size() == 1) {
                String confirmedSurface = unique.iterator().next();

                // Emit event only if the surface changed from the last emitted one
                if (lastEmittedSurface == null || !confirmedSurface.equals(lastEmittedSurface)) {
                    event = new Event(Event.TYPE_SURFACE_TYPE, position);
                    event.set(Position.KEY_SURFACE, confirmedSurface);
                    lastEmittedSurface = confirmedSurface;
                } else {
                    event = null;
                }
            }
        }

    }

}

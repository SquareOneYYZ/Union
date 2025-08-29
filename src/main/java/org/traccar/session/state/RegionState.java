package org.traccar.session.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Event;
import org.traccar.model.Position;

public class RegionState {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionState.class);
    private String lastCountry;
    private String lastState;
    private String lastCity;
    private Event enterEvent;
    private Event exitEvent;

    public void updateRegion(String country, String state, String city, Position position) {
        boolean regionChanged = (lastCountry == null || !lastCountry.equals(country)) ||
                (lastState == null || !lastState.equals(state)) ||
                (lastCity == null || !lastCity.equals(city));

        if (regionChanged) {
            // Exit event for previous region (if any)
            if (lastCountry != null || lastState != null || lastCity != null) {
                exitEvent = new Event(Event.TYPE_REGION_EXIT, position);
                exitEvent.set(Position.KEY_COUNTRY, lastCountry);
                exitEvent.set(Position.KEY_STATE, lastState);
                exitEvent.set(Position.KEY_CITY, lastCity);
            }

            // Enter event for new region
            enterEvent = new Event(Event.TYPE_REGION_ENTER, position);
            enterEvent.set(Position.KEY_COUNTRY, country);
            enterEvent.set(Position.KEY_STATE, state);
            enterEvent.set(Position.KEY_CITY, city);

            lastCountry = country;
            lastState = state;
            lastCity = city;
        } else {
            enterEvent = null;
            exitEvent = null;
        }
    }

    public Event getEnterEvent() {
        return enterEvent;
    }

    public Event getExitEvent() {
        return exitEvent;
    }

}

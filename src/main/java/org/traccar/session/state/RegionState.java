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
    private Event countryEnterEvent;
    private Event countryExitEvent;
    private Event stateEnterEvent;
    private Event stateExitEvent;
    private Event cityEnterEvent;
    private Event cityExitEvent;


    public void updateRegion(String country, String state, String city, Position position) {
        countryEnterEvent = null;
        countryExitEvent = null;
        stateEnterEvent = null;
        stateExitEvent = null;
        cityEnterEvent = null;
        cityExitEvent = null;

        // Clear old generic events (kept for backward compatibility)
        enterEvent = null;
        exitEvent = null;

        // Country change
        if (lastCountry == null || !lastCountry.equals(country)) {
            if (lastCountry != null) {
                countryExitEvent = new Event(Event.TYPE_REGION_COUNTRY_EXIT, position);
                countryExitEvent.set(Position.KEY_COUNTRY, lastCountry);
            }
            if (country != null) {
                countryEnterEvent = new Event(Event.TYPE_REGION_COUNTRY_ENTER, position);
                countryEnterEvent.set(Position.KEY_COUNTRY, country);
            }
            lastCountry = country;
        }

        // State change
        if (lastState == null || !lastState.equals(state)) {
            if (lastState != null) {
                stateExitEvent = new Event(Event.TYPE_REGION_STATE_EXIT, position);
                stateExitEvent.set(Position.KEY_STATE, lastState);
            }
            if (state != null) {
                stateEnterEvent = new Event(Event.TYPE_REGION_STATE_ENTER, position);
                stateEnterEvent.set(Position.KEY_STATE, state);
            }
            lastState = state;
        }

        // City change
        if (lastCity == null || !lastCity.equals(city)) {
            if (lastCity != null) {
                cityExitEvent = new Event(Event.TYPE_REGION_CITY_EXIT, position);
                cityExitEvent.set(Position.KEY_CITY, lastCity);
            }
            if (city != null) {
                cityEnterEvent = new Event(Event.TYPE_REGION_CITY_ENTER, position);
                cityEnterEvent.set(Position.KEY_CITY, city);
            }
            lastCity = city;
        }
    }


    public Event getEnterEvent() {
        return enterEvent;
    }

    public Event getExitEvent() {
        return exitEvent;
    }

    public Event getCountryEnterEvent() {
        return countryEnterEvent;
    }
    public Event getCountryExitEvent() {
        return countryExitEvent;
    }
    public Event getStateEnterEvent() {
        return stateEnterEvent;
    }
    public Event getStateExitEvent() {
        return stateExitEvent;
    }
    public Event getCityEnterEvent() {
        return cityEnterEvent;
    }
    public Event getCityExitEvent() {
        return cityExitEvent;
    }


}

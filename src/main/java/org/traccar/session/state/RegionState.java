package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Event;
import org.traccar.model.Position;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RegionState {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionState.class);
    @JsonProperty("lastCountry")
    private String lastCountry;
    @JsonProperty("lastState")
    private String lastState;
    @JsonProperty("lastCity")
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

        enterEvent = null;
        exitEvent = null;

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

        if (lastState == null || !lastState.equals(state)) {
            if (lastState != null) {
                stateExitEvent = new Event(Event.TYPE_REGION_STATE_EXIT, position);
                stateExitEvent.set(Position.KEY_STATE, lastState);
                stateExitEvent.set(Position.KEY_COUNTRY, lastCountry);
            }
            if (state != null) {
                stateEnterEvent = new Event(Event.TYPE_REGION_STATE_ENTER, position);
                stateEnterEvent.set(Position.KEY_STATE, state);
            }
            lastState = state;
        }

        if (lastCity == null || !lastCity.equals(city)) {
            if (lastCity != null) {
                cityExitEvent = new Event(Event.TYPE_REGION_CITY_EXIT, position);
                cityExitEvent.set(Position.KEY_CITY, lastCity);
                cityExitEvent.set(Position.KEY_COUNTRY, lastCountry);
                cityExitEvent.set(Position.KEY_STATE, lastState);
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

    public Event setEnterEvent(Event enterEvent) {
        this.enterEvent = enterEvent;
        return enterEvent;
    }

    public Event getExitEvent() {
        return exitEvent;
    }
    public Event setExitEvent(Event exitEvent) {
        this.exitEvent = exitEvent;
        return exitEvent;
    }

    public Event getCountryEnterEvent() {
        return countryEnterEvent;
    }
    public Event setCountryEnterEvent(Event countryEnterEvent) {
        this.countryEnterEvent = countryEnterEvent;
        return countryEnterEvent;
    }
    public Event getCountryExitEvent() {
        return countryExitEvent;
    }
    public Event setCountryExitEvent(Event countryExitEvent) {
        this.countryExitEvent = countryExitEvent;
        return countryExitEvent;
    }
    public Event getStateEnterEvent() {
        return stateEnterEvent;
    }
    public Event setStateEnterEvent(Event stateEnterEvent) {
        this.stateEnterEvent = stateEnterEvent;
        return stateEnterEvent;
    }
    public Event getStateExitEvent() {
        return stateExitEvent;
    }
    public Event setStateExitEvent(Event stateExitEvent) {
        this.stateExitEvent = stateExitEvent;
        return stateExitEvent;
    }
    public Event getCityEnterEvent() {
        return cityEnterEvent;
    }
    public Event setCityEnterEvent(Event cityEnterEvent) {
        this.cityEnterEvent = cityEnterEvent;
        return cityEnterEvent;
    }
    public Event getCityExitEvent() {
        return cityExitEvent;
    }
    public Event setCityExitEvent(Event cityExitEvent) {
        this.cityExitEvent = cityExitEvent;
        return cityExitEvent;
    }


}

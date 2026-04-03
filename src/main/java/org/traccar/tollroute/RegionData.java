package org.traccar.tollroute;

public class RegionData {
    private final String country;
    private final String state;
    private final String city;

    public RegionData(String country, String state, String city) {
        this.country = country;
        this.state = state;
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public String getState() {
        return state;
    }

    public String getCity() {
        return city;
    }
}

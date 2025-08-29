package org.traccar.tollroute;

public class TollData {
    private final Boolean toll;
    private final String ref;
    private final String name;
    private final String surface;

    private final String country;
    private final String state;
    private final String city;

    public TollData(Boolean toll, String ref, String name, String surface,
                    String country, String state, String city) {
        this.toll = toll;
        this.ref = ref;
        this.name = name;
        this.surface = surface;
        this.country = country;
        this.state = state;
        this.city = city;
    }

    public Boolean getToll() {
        return toll;
    }

    public String getRef() {
        return ref;
    }

    public String getName() {
        return name;
    }
    public String getSurface() {
        return this.surface;
    }
    public String getCountry() { return country; }
    public String getState() { return state; }
    public String getCity() { return city; }

}

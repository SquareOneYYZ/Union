package org.traccar.tollroute;

public class TollData {
    private final Boolean toll;
    private final String ref;
    private final String name;
    private final String surface;

    public TollData(Boolean toll, String ref, String name, String surface) {
        this.toll = toll;
        this.ref = ref;
        this.name = name;
        this.surface = surface;
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

}

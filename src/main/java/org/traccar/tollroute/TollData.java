package org.traccar.tollroute;

public class TollData {
    private final Boolean toll;
    private final String ref;
    private final String name;
    private final String surface;
    private final String highway;
    private final String enforcement;

    public TollData(
            Boolean toll, String ref, String name, String surface,
            String highway, String enforcement
    ) {
        this.toll = toll;
        this.ref = ref;
        this.name = name;
        this.surface = surface;
        this.highway = highway;
        this.enforcement = enforcement;
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

    public String getHighway() {
        return highway;
    }

    public String getEnforcement() {
        return enforcement;
    }

}

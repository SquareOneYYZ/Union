
package org.traccar.model;

import org.traccar.storage.StorageName;

@StorageName("tc_regions")
public class Region extends ExtendedModel {

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    private String country;
    private String state;

    public String getCountry() {
        return country;
    }
    public void setCountry(String country) {
        this.country = country;
    }

    public String getState() {
        return state;
    }
    public void setState(String state) {
        this.state = state;
    }

}

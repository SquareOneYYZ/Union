package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Map;


@JsonIgnoreProperties(ignoreUnknown = true)
public class TollRouteState {

    private static final Logger LOGGER = LoggerFactory.getLogger(TollRouteState.class);

    public static final int WINDOW_SIZE = 6;
    public static final double THRESHOLD_PERCENT = 80.0;


    @JsonProperty
    private long id;

    @JsonProperty
    private boolean changed;

    @JsonProperty
    private ConfidenceWindow<Boolean> tollWindow =
            new ConfidenceWindow<>(WINDOW_SIZE, THRESHOLD_PERCENT);

    @JsonProperty
    private ConfidenceWindow<Boolean> customTollWindow =
            new ConfidenceWindow<>(WINDOW_SIZE, THRESHOLD_PERCENT);

    @JsonProperty
    private String lastCustomTollName;

    @JsonProperty
    private double tollStartDistance;

    @JsonProperty
    private double tollExitDistance;

    @JsonProperty
    private Date tollrouteTime;

    @JsonProperty
    private String tollRef;

    @JsonProperty
    private String tollName;

    @JsonIgnore
    private Event event;


    public void fromDevice(Device device) {
        if (device.hasAttribute(Position.KEY_TOLL_NAME)) {
            this.tollName = device.getString(Position.KEY_TOLL_NAME);
        }
        if (device.hasAttribute(Position.KEY_TOLL_REF)) {
            this.tollRef = device.getString(Position.KEY_TOLL_REF);
        }
        this.tollStartDistance = device.getTollStartDistance();
        this.tollExitDistance = device.getDouble(Position.KEY_TOLL_EXIT);
        this.tollrouteTime = device.getTollrouteTime();
        this.id = device.getId();
    }

    public void toDevice(Device device) {
        if (tollName != null) {
            device.set(Position.KEY_TOLL_NAME, tollName);
        }
        if (tollRef != null) {
            device.set(Position.KEY_TOLL_REF, tollRef);
        }
        device.set(Position.KEY_TOLL_EXIT, tollExitDistance);

        if (event != null && event.getType().equals(Event.TYPE_DEVICE_TOLLROUTE_EXIT)) {
            Map<String, Object> deviceAttributes = device.getAttributes();
            deviceAttributes.remove(Position.KEY_TOLL_REF);
            deviceAttributes.remove(Position.KEY_TOLL_NAME);
            deviceAttributes.remove(Position.KEY_TOLL_EXIT);
            device.setAttributes(deviceAttributes);
        }

        device.setTollStartDistance(tollStartDistance);
        device.setTollrouteTime(tollrouteTime);
    }


    public void addOnToll(Boolean isToll, int duration) {
        if (tollWindow == null) {
            tollWindow = new ConfidenceWindow<>(WINDOW_SIZE, THRESHOLD_PERCENT);
        }
        tollWindow.add(isToll);
        LOGGER.debug("TollWindow updated: added={}, window={}", isToll, tollWindow.getWindow());
    }


    public Boolean isOnToll(int duration) {
        if (tollWindow == null) {
            return null;
        }
        if (!tollWindow.getWindow().isEmpty()
                && tollWindow.getWindow().stream().allMatch(v -> Boolean.FALSE.equals(v))) {
            LOGGER.debug("TollWindow: all values are false, returning false early.");
            return false;
        }
        Boolean dominant = tollWindow.getDominantValue();
        if (dominant != null) {
            LOGGER.info("TollWindow confirmed: {} (threshold={}%, window={})",
                    dominant, THRESHOLD_PERCENT, tollWindow.getWindow());
        }
        return dominant;
    }



    public void addOnCustomToll(boolean match, int duration) {
        if (customTollWindow == null) {
            customTollWindow = new ConfidenceWindow<>(WINDOW_SIZE, THRESHOLD_PERCENT);
        }
        customTollWindow.add(match);
        LOGGER.debug("CustomTollWindow updated: added={}, window={}", match,
                customTollWindow.getWindow());
    }


    public boolean isCustomTollConfirmed(int duration) {
        if (customTollWindow == null) {
            return false;
        }
        boolean confirmed = customTollWindow.isDominantTrue();
        if (confirmed) {
            LOGGER.debug("CustomTollWindow confirmed (threshold={}%, window={})",
                    THRESHOLD_PERCENT, customTollWindow.getWindow());
        }
        return confirmed;
    }


    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public boolean isChanged() {
        return changed;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    public ConfidenceWindow<Boolean> getTollWindow() {
        return tollWindow;
    }

    public void setTollWindow(ConfidenceWindow<Boolean> tollWindow) {
        this.tollWindow = tollWindow;
    }

    public ConfidenceWindow<Boolean> getCustomTollWindow() {
        return customTollWindow;
    }

    public void setCustomTollWindow(ConfidenceWindow<Boolean> customTollWindow) {
        this.customTollWindow = customTollWindow;
    }

    public String getLastCustomTollName() {
        return lastCustomTollName;
    }

    public void setLastCustomTollName(String tollName) {
        this.lastCustomTollName = tollName;
    }

    public double getTollStartDistance() {
        return tollStartDistance;
    }

    public void setTollStartDistance(double tollStartDistance) {
        this.changed = true;
        this.tollStartDistance = tollStartDistance;
    }

    public double getTollExitDistance() {
        return tollExitDistance;
    }

    public void setTollExitDistance(double tollExitDistance) {
        this.changed = true;
        this.tollExitDistance = tollExitDistance;
    }

    public Date getTollrouteTime() {
        return tollrouteTime;
    }

    public void setTollrouteTime(Date tollrouteTime) {
        this.changed = true;
        this.tollrouteTime = tollrouteTime;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    public String getTollRef() {
        return tollRef;
    }

    public void setTollRef(String tollRef) {
        if (tollRef != null && (this.tollRef == null || !tollRef.equals(this.tollRef))) {
            this.changed = true;
            this.tollRef = tollRef;
        }
    }

    public String getTollName() {
        return tollName;
    }

    public void setTollName(String tollName) {
        if (tollName != null && (this.tollName == null || !tollName.equals(this.tollName))) {
            this.changed = true;
            this.tollName = tollName;
        }
    }
}

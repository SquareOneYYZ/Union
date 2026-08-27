package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.*;

import static org.traccar.handler.events.TollEventHandler.LOGGER;

/**
 * Per-device toll state, serialised into {@code toll:<deviceId>} by
 * {@code TollEventHandler:164-169}.
 *
 * <p>{@code TollEventHandler:49} builds a plain {@code ObjectMapper}, so Jackson's
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} is enabled. Without {@code ignoreUnknown}, rolling a jar
 * back to a version whose class has fewer fields than the payload in Redis makes every read
 * throw - caught at {@code TollEventHandler:101-103}, which logs a WARN and nulls the state,
 * so every device loses its window with a warning per position until the keys are rewritten.
 *
 * <p>The annotation only helps in the version being rolled back <em>to</em>, which is why it
 * ships here with 1a and 1b rather than with the fields 1c adds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TollRouteState {
    private static final Logger LOGGER = LoggerFactory.getLogger(TollRouteState.class);


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

    @JsonProperty
    private long id;
    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }

    @JsonProperty
    private boolean changed;

    public boolean isChanged() {
        return changed;
    }
    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    @JsonProperty
    public List<Boolean> getTollWindow() {
        return tollWindow;
    }

    public void setTollWindow(List<Boolean> tollWindow) {
        this.tollWindow = tollWindow;
    }



    // private List<Boolean> tollWindow;
    @JsonProperty
    private List<Boolean> tollWindow = new ArrayList<>();

    @JsonProperty
    private List<Boolean> customTollWindow = new ArrayList<>();

    @JsonProperty
    private String lastCustomTollName;

    public void addOnCustomToll(boolean match, int duration) {
        if (this.customTollWindow == null) {
            this.customTollWindow = new ArrayList<>();
        }
        this.customTollWindow.add(match);
        LOGGER.debug("CustomTollWindow added value: {}, current size: {}, values: {}", match,
                this.customTollWindow.size(), this.customTollWindow);
        if (this.customTollWindow.size() > duration) {
            Boolean removed = this.customTollWindow.remove(0);
            LOGGER.debug("CustomTollWindow removed oldest value: {}, new size: {}, values: {}", removed,
                    this.customTollWindow.size(), this.customTollWindow);
        }
        if (this.customTollWindow.size() == duration) {
            LOGGER.debug("CustomTollWindow reached required size {} with values: {}", duration, this.customTollWindow);
        }
    }

    public boolean isCustomTollConfirmed(int duration) {
        if (this.customTollWindow != null
                && this.customTollWindow.size() == duration) {
            Set<Boolean> set = new HashSet<>(this.customTollWindow);
            return set.size() == 1 && set.contains(true);
        }
        return false;
    }

    public String getLastCustomTollName() {
        return lastCustomTollName;
    }

    public void setLastCustomTollName(String tollName) {
        this.lastCustomTollName = tollName;
    }


/*
    public void addOnToll(Boolean isToll, int duration) {
        if (this.tollWindow == null) {
            this.tollWindow = new ArrayList<Boolean>();
        }
        this.tollWindow.add(isToll);
        LOGGER.info("TollWindow added value: {}, current size: {}, values: {}", isToll,
         this.tollWindow.size(), this.tollWindow);
        if (this.tollWindow.size() > duration) {
            this.tollWindow.remove(0);
        }
    }
*/

    /**
     * Appends one reading to the confirmation window.
     *
     * <p>A {@code null} is an unknown - the enrichment gate skipped the lookup, or the lookup
     * failed - and is neither a confirmation nor a contradiction. It does not enter the window
     * and it does not reset it, so {@code duration} slots always mean {@code duration} real
     * lookups. Appending it instead would put a third value into a set the homogeneity test at
     * {@link #isOnToll(int)} requires to have exactly one member, and no event would ever fire.
     *
     * <p>The trim is a {@code while}, not an {@code if}. A single {@code if} removes at most one
     * entry per call, so a window restored longer than {@code duration} grows by one and shrinks
     * by one on every position and never converges - leaving {@link #isOnToll(int)}, whose size
     * test is an equality, returning null for that device forever.
     */
    public void addOnToll(Boolean isToll, int duration) {
        if (this.tollWindow == null) {
            this.tollWindow = new ArrayList<>();
        }

        if (isToll == null) {
            return;
        }

        this.tollWindow.add(isToll);

        while (this.tollWindow.size() > duration) {
            this.tollWindow.remove(0);
        }

        if (this.tollWindow.size() == duration) {
            LOGGER.info("TollWindow reached required size {} with values: {}", duration, this.tollWindow);
        }
    }



    public Boolean isOnToll(int duration) {
        Set<Boolean> tollWindowSet = null;
        if (this.tollWindow != null) {
            tollWindowSet = new HashSet<>(this.tollWindow);
        }
        if (tollWindowSet != null && tollWindowSet.size() == 1) {
            if (this.tollWindow.size() == (int) duration) {
                LOGGER.info("TollWindow reached required size {} with same value: {}",
                        duration, tollWindowSet.iterator().next());
                return tollWindowSet.iterator().next();
            } else if (this.tollWindow.size() < duration && tollWindowSet.contains(false)) {
                LOGGER.info("TollWindow not yet at required size {}, but contains false", duration);
                return false;
            }
        }
        return null;
    }



    @JsonProperty
    private double tollStartDistance;

    public double getTollStartDistance() {
        return tollStartDistance;
    }

    public void setTollStartDistance(double tollStartDistance) {
        this.changed = true;
        this.tollStartDistance = tollStartDistance;
    }

    @JsonProperty
    private double tollExitDistance;

    public double getTollExitDistance() {
        return tollExitDistance;
    }

    public void setTollExitDistance(double tollExitDistance) {
        this.changed = true;
        this.tollExitDistance = tollExitDistance;
    }

    @JsonProperty
    private Date tollrouteTime;

    public Date getTollrouteTime() {
        return tollrouteTime;
    }

    public void setTollrouteTime(Date tollrouteTime) {
        this.changed = true;
        this.tollrouteTime = tollrouteTime;
    }

    @JsonIgnore
    private Event event;

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    @JsonProperty
    private String tollRef;

    public String getTollRef() {
        return tollRef;
    }

    public void setTollRef(String tollRef) {
        if (tollRef != null) {
            if (this.tollRef == null || !tollRef.equals(this.tollRef)) {
                this.changed = true;
                this.tollRef = tollRef;
            }
        }
    }

    @JsonProperty
    private String tollName;

    public String getTollName() {
        return tollName;
    }
/*
    public void setTollName(String tollName) {
        this.changed = true;
        this.tollName = tollName;
    }
*/

    public void setTollName(String tollName) {
        if (tollName != null) {
            if (this.tollName == null || !tollName.equals(this.tollName)) {
                this.changed = true;
                this.tollName = tollName;
            }
        }
    }



}

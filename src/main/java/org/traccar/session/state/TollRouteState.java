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
        addOnToll(isToll, duration, null);
    }

    /**
     * As {@link #addOnToll(Boolean, int)}, additionally remembering which position opened the
     * current homogeneous run and which was the most recent confirmation.
     *
     * <p>Those two marks are what let the emitted event carry the boundary of the traversal
     * rather than the position that happened to complete the window. Without them the enter is
     * {@code duration} lookups late - up to {@code duration x} the gate distance, which is 3 km
     * at the shipped 500 m and 6.
     *
     * @param position the position this reading came from, or {@code null} to skip mark keeping
     */
    public void addOnToll(Boolean isToll, int duration, Position position) {
        if (this.tollWindow == null) {
            this.tollWindow = new ArrayList<>();
        }

        if (isToll == null) {
            return;
        }

        if (position != null) {
            // A new run begins whenever the reading differs from the previous one. runValue is
            // null for state restored from a payload written before this field existed, which
            // is treated as a new run so the first traversal after deploy behaves as it did
            // before rather than backdating to a default.
            if (this.runValue == null || !this.runValue.equals(isToll)) {
                this.runValue = isToll;
                this.runStart = PositionMark.of(position);
            }
            if (isToll) {
                this.lastTrue = PositionMark.of(position);
            }
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

    /**
     * A position reduced to the four values an event needs, so the state can remember a
     * traversal boundary without serialising a whole {@link Position} into
     * {@code toll:<deviceId>}.
     *
     * <p>Both times are kept because the two consumers disagree: {@code Event(String, Position)}
     * takes {@code getDeviceTime()} for {@code eventTime}, while {@code stateStartToll} records
     * {@code getFixTime()} as {@code tollrouteTime}. Storing one and reusing it for both would
     * silently change whichever it was not.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PositionMark {

        @JsonProperty
        private long positionId;

        @JsonProperty
        private Date deviceTime;

        @JsonProperty
        private Date fixTime;

        @JsonProperty
        private double totalDistance;

        public PositionMark() {
        }

        static PositionMark of(Position position) {
            PositionMark mark = new PositionMark();
            mark.positionId = position.getId();
            mark.deviceTime = position.getDeviceTime();
            mark.fixTime = position.getFixTime();
            mark.totalDistance = position.getDouble(Position.KEY_TOTAL_DISTANCE);
            return mark;
        }

        public long getPositionId() {
            return positionId;
        }

        public void setPositionId(long positionId) {
            this.positionId = positionId;
        }

        public Date getDeviceTime() {
            return deviceTime;
        }

        public void setDeviceTime(Date deviceTime) {
            this.deviceTime = deviceTime;
        }

        public Date getFixTime() {
            return fixTime;
        }

        public void setFixTime(Date fixTime) {
            this.fixTime = fixTime;
        }

        public double getTotalDistance() {
            return totalDistance;
        }

        public void setTotalDistance(double totalDistance) {
            this.totalDistance = totalDistance;
        }
    }

    /** The reading that opened the current homogeneous run, true or false. */
    @JsonProperty
    private PositionMark runStart;

    /** The most recent confirmation, which is where a traversal ends. */
    @JsonProperty
    private PositionMark lastTrue;

    /** The value of the current run, used to detect where one run ends and the next begins. */
    @JsonProperty
    private Boolean runValue;

    public PositionMark getRunStart() {
        return runStart;
    }

    public void setRunStart(PositionMark runStart) {
        this.runStart = runStart;
    }

    public PositionMark getLastTrue() {
        return lastTrue;
    }

    public void setLastTrue(PositionMark lastTrue) {
        this.lastTrue = lastTrue;
    }

    public Boolean getRunValue() {
        return runValue;
    }

    public void setRunValue(Boolean runValue) {
        this.runValue = runValue;
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

package org.traccar.tollpricing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.model.TollTraversal;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Builds the one row a toll exit produces, from values the job has already read. No I/O, so every
 * outcome - priced, unknown, unmatched - is unit-testable. Every exit yields exactly one row: an
 * unpriceable exit gets a reason rather than being skipped, which is what keeps the job's watermark
 * (the highest exit event id in the table) moving.
 */
public final class TraversalBuilder {

    public static final String REASON_NO_ENTER_EVENT = "NO_ENTER_EVENT";
    public static final String REASON_NO_POSITION = "NO_POSITION";
    public static final String REASON_NO_FACILITY = "NO_FACILITY";
    public static final String REASON_ERROR = "ERROR";

    /** Fleet constants (D2/D3): light vehicles, billed by licence plate. Per-device override later. */
    public static final String VEHICLE_CLASS = "light";
    public static final String PAYMENT_METHOD = "license_plate";

    /** A traversal edge: the position an enter or exit event points at. */
    public record Edge(long positionId, double latitude, double longitude, Date fixTime) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FacilityRegistry registry;

    public TraversalBuilder(FacilityRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param enterEventId 0 when no enter event precedes this exit
     * @param enter        null when there is no enter event or its position is gone
     * @param exit         null when the exit's position is gone
     * @param tollDistance the exit event's odometer distance, metres
     */
    public TollTraversal build(long deviceId, long exitEventId, long enterEventId,
                               Edge enter, Edge exit, double tollDistance, Date now) {
        return build(deviceId, exitEventId, enterEventId, enter, exit, tollDistance, now, () -> null, null);
    }

    /**
     * @param trail      reads the positions to test against flat facilities' toll points; only called
     *                   when the traversal is not on a distance-zoned corridor
     * @param trailStart where that trail starts (the enter, or an earlier fragment's exit), for offsets
     */
    public TollTraversal build(long deviceId, long exitEventId, long enterEventId,
                               Edge enter, Edge exit, double tollDistance, Date now,
                               Supplier<List<PassageDetector.TrailPoint>> trail, Date trailStart) {
        TollTraversal row = new TollTraversal();
        row.setDeviceId(deviceId);
        row.setExitEventId(exitEventId);
        row.setEnterEventId(enterEventId);
        row.setDistance(tollDistance);
        row.setVehicleClass(VEHICLE_CLASS);
        row.setPaymentMethod(PAYMENT_METHOD);
        row.setCalcVersion(TollEstimate.CALC_VERSION);
        row.setPricedAt(now);
        if (enter != null) {
            row.setEnterPositionId(enter.positionId());
            row.setEnterTime(enter.fixTime());
            row.setEnterLatitude(enter.latitude());
            row.setEnterLongitude(enter.longitude());
        }
        if (exit != null) {
            row.setExitPositionId(exit.positionId());
            row.setExitTime(exit.fixTime());
            row.setExitLatitude(exit.latitude());
            row.setExitLongitude(exit.longitude());
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        if (enterEventId == 0) {
            return unpriced(row, REASON_NO_ENTER_EVENT, inputs);
        }
        if (enter == null || exit == null || enter.fixTime() == null) {
            return unpriced(row, REASON_NO_POSITION, inputs);
        }
        Optional<FacilityRegistry.Facility> facility = registry.match(
                enter.latitude(), enter.longitude(), exit.latitude(), exit.longitude());
        if (facility.isEmpty()) {
            List<GantrySet> flat = registry.flatNear(
                    enter.latitude(), enter.longitude(), exit.latitude(), exit.longitude());
            if (flat.isEmpty()) {
                return unpriced(row, REASON_NO_FACILITY, inputs);
            }
            return priceFlat(row, flat, trail, trailStart != null ? trailStart : enter.fixTime(), inputs);
        }
        row.setFacilityId(facility.get().id());
        row.setCurrency(facility.get().currency());
        try {
            DistanceZonedPricer.Result result = facility.get().pricer().price(
                    enter.latitude(), enter.longitude(), enter.fixTime().toInstant(),
                    exit.latitude(), exit.longitude());
            TollEstimate e = result.estimate();
            row.setAmount(e.getAmount());
            row.setReason(e.getReason());
            row.setDayType(e.getDayType());
            row.setBand(e.getBand());
            row.setCardValidFrom(e.getCardValidFrom());
            Object direction = result.inputs().get("direction");
            row.setDirection(direction != null ? direction.toString() : null);
            inputs.putAll(result.inputs());
            inputs.put("lines", e.getLines());
        } catch (Exception e) {
            inputs.put("error", e.toString());
            return unpriced(row, REASON_ERROR, inputs);
        }
        row.setInputs(json(inputs));
        return row;
    }

    private static TollTraversal priceFlat(TollTraversal row, List<GantrySet> sets,
                                           Supplier<List<PassageDetector.TrailPoint>> trail, Date start,
                                           Map<String, Object> inputs) {
        try {
            FlatPricer.Result result = FlatPricer.price(trail.get(), sets, start);
            TollEstimate e = result.estimate();
            row.setFacilityId(result.facilityId());
            row.setCurrency(e.getCurrency());
            row.setAmount(e.getAmount());
            row.setReason(e.getReason());
            row.setDayType(e.getDayType());
            row.setCardValidFrom(e.getCardValidFrom());
            inputs.putAll(result.inputs());
            if (start != null && row.getEnterTime() != null && start.before(row.getEnterTime())) {
                inputs.put("trailFrom", "previousFragmentExit");
            }
        } catch (RuntimeException e) {
            inputs.put("error", e.toString());
            return unpriced(row, REASON_ERROR, inputs);
        }
        row.setInputs(json(inputs));
        return row;
    }

    private static TollTraversal unpriced(TollTraversal row, String reason, Map<String, Object> inputs) {
        row.setAmount(null);
        row.setReason(reason);
        row.setInputs(json(inputs));
        return row;
    }

    private static String json(Map<String, Object> inputs) {
        try {
            return MAPPER.writeValueAsString(inputs);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"inputs not serialisable\"}";
        }
    }
}

package org.traccar.tollpricing;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prices one traversal of a distance-zoned facility from its two edges: project both onto the
 * corridor, snap outward to interchanges, split km by zone, take the band at entry time in the
 * facility's own time zone, and price against the card.
 *
 * <p>Returns the estimate together with every input that produced it, so a later calc version
 * can re-estimate without the positions (decision: persist inputs).
 */
public final class DistanceZonedPricer {

    public static final String REASON_OFF_CORRIDOR = "OFF_CORRIDOR";

    public record Result(TollEstimate estimate, Map<String, Object> inputs) {
    }

    private final DistanceZonedCard card;
    private final ZonedCorridor corridor;
    private final ZoneId zone;
    private final OperatorHolidays holidays;

    public DistanceZonedPricer(DistanceZonedCard card, ZonedCorridor corridor, ZoneId zone, OperatorHolidays holidays) {
        this.card = card;
        this.corridor = corridor;
        this.zone = zone;
        this.holidays = holidays;
    }

    public Result price(double entryLat, double entryLon, Instant entryTime,
                        double exitLat, double exitLon) throws IOException {
        Map<String, Object> inputs = new LinkedHashMap<>();
        LocalDateTime entryLocal = LocalDateTime.ofInstant(entryTime, zone);
        boolean holiday = holidays.isHoliday(entryLocal.toLocalDate());
        inputs.put("entryLocal", entryLocal.toString());
        inputs.put("holiday", holiday);
        inputs.put("cardValidFrom", card.getValidFrom());
        inputs.put("osmBase", corridor.getOsmBase());

        ZonedCorridor.Projection in = corridor.project(entryLat, entryLon);
        ZonedCorridor.Projection out = corridor.project(exitLat, exitLon);
        if (in == null || out == null) {
            inputs.put("offCorridor", in == null ? "entry" : "exit");
            return new Result(TollEstimate.unknown(REASON_OFF_CORRIDOR, card.getCurrency(), null, null,
                    card.getValidFrom()), inputs);
        }
        inputs.put("entryKm", round3(in.km()));
        inputs.put("exitKm", round3(out.km()));
        inputs.put("entryOffsetM", Math.round(in.offsetMeters()));
        inputs.put("exitOffsetM", Math.round(out.offsetMeters()));

        ZonedCorridor.SnappedTrip trip = corridor.snap(in.km(), out.km());
        inputs.put("snapToleranceKm", ZonedCorridor.SNAP_TOLERANCE_KM);
        inputs.put("direction", trip.direction());
        inputs.put("entryExit", trip.entry() != null ? trip.entry().getExit() : "terminus");
        inputs.put("exitExit", trip.exit() != null ? trip.exit().getExit() : "terminus");
        inputs.put("zoneKm", trip.zoneKm());
        if (trip.beyondEnd()) {
            // Continued onto the next operator's road (407 East): priced for this facility only (gap 2 C).
            inputs.put("mixedOperators", true);
        }

        TollEstimate estimate = DistanceZonedEstimator.price(
                card, entryLocal, trip.direction(), trip.zoneKm(), holiday);
        return new Result(estimate, inputs);
    }

    public String getCardValidFrom() {
        return card.getValidFrom();
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}

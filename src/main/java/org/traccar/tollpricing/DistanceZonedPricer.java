package org.traccar.tollpricing;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * @param facilityId the facility the row is charged to when it is not this pricer's own (a trip
     *                   wholly on the continuation); null means this pricer's facility
     */
    public record Result(TollEstimate estimate, Map<String, Object> inputs, String facilityId) {
        public Result(TollEstimate estimate, Map<String, Object> inputs) {
            this(estimate, inputs, null);
        }
    }

    /**
     * The road past the corridor's far end, under another operator, toll-free from {@code tollFreeSince}
     * (the MTO's Highway 407 East beyond Brock Road, free from 2025-06-01). Its positions project onto
     * the corridor's extended last segment (under 1 km off it as far as Highway 35/115), so a trip there
     * would otherwise be read as a zero-km ETR trip.
     */
    public record Continuation(String facilityId, LocalDate tollFreeSince) {
    }

    private final DistanceZonedCard card;
    private final ZonedCorridor corridor;
    private final ZoneId zone;
    private final OperatorHolidays holidays;
    private final Continuation continuation;

    public DistanceZonedPricer(DistanceZonedCard card, ZonedCorridor corridor, ZoneId zone, OperatorHolidays holidays) {
        this(card, corridor, zone, holidays, null);
    }

    public DistanceZonedPricer(DistanceZonedCard card, ZonedCorridor corridor, ZoneId zone, OperatorHolidays holidays,
                               Continuation continuation) {
        this.card = card;
        this.corridor = corridor;
        this.zone = zone;
        this.holidays = holidays;
        this.continuation = continuation;
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

        double end = corridor.getEndKm() + ZonedCorridor.SNAP_TOLERANCE_KM;
        boolean entryBeyond = in.km() > end;
        boolean exitBeyond = out.km() > end;
        if (continuation != null && (entryBeyond || exitBeyond)) {
            inputs.put("continuation", continuation.facilityId());
            inputs.put("continuationTollFreeSince", continuation.tollFreeSince().toString());
            boolean free = !entryLocal.toLocalDate().isBefore(continuation.tollFreeSince());
            if (entryBeyond && exitBeyond) {
                // Wholly on the continuation: nothing to charge once it is free; no card for it before.
                return new Result(TollEstimate.unknown(free ? TollEstimate.REASON_NOT_BILLABLE
                        : TollEstimate.REASON_NO_RATE, card.getCurrency(), null, null, null),
                        inputs, continuation.facilityId());
            }
            if (!free) {
                // Partly on the continuation while it was still tolled: its portion has no card.
                inputs.put("mixedOperators", true);
                return new Result(TollEstimate.unknown(TollEstimate.REASON_NO_RATE, card.getCurrency(), null, null,
                        card.getValidFrom()), inputs);
            }
        }

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

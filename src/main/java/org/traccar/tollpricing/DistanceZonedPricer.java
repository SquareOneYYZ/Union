package org.traccar.tollpricing;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

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

    /** An edge this close to the continuation's own shape is on it (GPS error and carriageway spread). */
    static final double ON_CONTINUATION_M = 300;

    /**
     * The road past the corridor's far end, under another operator, toll-free from {@code tollFreeSince}
     * (the MTO's Highway 407 East beyond Brock Road, free from 2025-06-01), with its own shape as [lat, lon]
     * lines (corridors/407-east.json). The corridor's extended last segment is no stand-in for it: 407 East
     * drifts up to 2.6 km off that line, past the corridor's 2 km projection limit.
     */
    public record Continuation(String facilityId, LocalDate tollFreeSince, List<double[][]> lines) {

        /** Metres from a position to the continuation's shape (local equirectangular). */
        double distanceM(double lat, double lon) {
            double best = Double.MAX_VALUE;
            double k = Math.cos(Math.toRadians(lat)) * METRES_PER_DEG;
            for (double[][] line : lines) {
                for (int i = 1; i < line.length; i++) {
                    double ax = (line[i - 1][1] - lon) * k;
                    double ay = (line[i - 1][0] - lat) * METRES_PER_DEG;
                    double bx = (line[i][1] - lon) * k;
                    double by = (line[i][0] - lat) * METRES_PER_DEG;
                    double dx = bx - ax;
                    double dy = by - ay;
                    double len = dx * dx + dy * dy;
                    double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
                    best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
                }
            }
            return best;
        }
    }

    private static final double METRES_PER_DEG = 111195;

    /** Rate cards by {@code valid_from}; a trip is priced by the latest card in force on its entry date. */
    private final NavigableMap<LocalDate, DistanceZonedCard> cards = new TreeMap<>();
    private final ZonedCorridor corridor;
    private final ZoneId zone;
    private final OperatorHolidays holidays;
    private final Continuation continuation;

    public DistanceZonedPricer(DistanceZonedCard card, ZonedCorridor corridor, ZoneId zone, OperatorHolidays holidays) {
        this(List.of(card), corridor, zone, holidays, null);
    }

    /**
     * @param cards one per rate period (407 ETR reprices every 1 January); distinct {@code valid_from},
     *              one currency. A new year's card can ship before it takes effect: no redeploy on the day.
     */
    public DistanceZonedPricer(List<DistanceZonedCard> cards, ZonedCorridor corridor, ZoneId zone,
                               OperatorHolidays holidays, Continuation continuation) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("no rate card");
        }
        for (DistanceZonedCard card : cards) {
            if (this.cards.put(LocalDate.parse(card.getValidFrom()), card) != null) {
                throw new IllegalArgumentException("two cards valid from " + card.getValidFrom());
            }
            if (!card.getCurrency().equals(cards.get(0).getCurrency())) {
                throw new IllegalArgumentException("cards in different currencies: " + card.getValidFrom());
            }
        }
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
        // ETR prices the whole trip by entry time, so the card is the one in force on the entry date.
        Map.Entry<LocalDate, DistanceZonedCard> inForce = cards.floorEntry(entryLocal.toLocalDate());
        // Before every card there is no rate; the earliest card still names the currency and the date.
        DistanceZonedCard card = inForce != null ? inForce.getValue() : cards.firstEntry().getValue();
        inputs.put("entryLocal", entryLocal.toString());
        inputs.put("holiday", holiday);
        inputs.put("cardValidFrom", card.getValidFrom());
        inputs.put("osmBase", corridor.getOsmBase());

        ZonedCorridor.Projection in = corridor.project(entryLat, entryLon);
        ZonedCorridor.Projection out = corridor.project(exitLat, exitLon);
        double end = corridor.getEndKm() + ZonedCorridor.SNAP_TOLERANCE_KM;
        boolean entryOnContinuation = onContinuation(in, entryLat, entryLon, end);
        boolean exitOnContinuation = onContinuation(out, exitLat, exitLon, end);
        if (entryOnContinuation || exitOnContinuation) {
            inputs.put("continuation", continuation.facilityId());
            inputs.put("continuationTollFreeSince", continuation.tollFreeSince().toString());
            boolean free = !entryLocal.toLocalDate().isBefore(continuation.tollFreeSince());
            if (entryOnContinuation && exitOnContinuation) {
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
            // The edge on the continuation joins the corridor at its far end (Brock Road): price to there.
            ZonedCorridor.Projection farEnd = new ZonedCorridor.Projection(end + 1, 0);
            if (entryOnContinuation) {
                in = farEnd;
                inputs.put("entryOnContinuation", true);
            } else {
                out = farEnd;
                inputs.put("exitOnContinuation", true);
            }
        }
        if (inForce == null) {
            // Before the loaded card: unknown, never a later card's price (as the flat facilities).
            return new Result(TollEstimate.unknown(TollEstimate.REASON_NO_RATE, card.getCurrency(), null, null,
                    card.getValidFrom()), inputs);
        }

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

    /** An edge past the corridor's end, or off it, that lies on the continuation's own shape. */
    private boolean onContinuation(ZonedCorridor.Projection p, double lat, double lon, double end) {
        return continuation != null && (p == null || p.km() > end)
                && continuation.distanceM(lat, lon) <= ON_CONTINUATION_M;
    }

    /** The loaded cards' {@code valid_from}, oldest first. */
    public List<String> getCards() {
        return cards.values().stream().map(DistanceZonedCard::getValidFrom).toList();
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}

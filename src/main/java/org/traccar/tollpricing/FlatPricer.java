package org.traccar.tollpricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prices a traversal of flat-rate (per-plaza) facilities from the passages its trail made.
 *
 * <p>Each passage is charged by the facility that owns the toll point (decision 23, gap 2 A), so a
 * trip that crosses operators is split correctly; the row's facility is the one with the largest
 * subtotal and {@code breakdown} lists each. Each passage is charged from the card in effect when it
 * happened, so a trip across a reprice midnight is split correctly too. No passage means unknown,
 * not zero; a passage no card covers makes the whole trip unknown.
 */
public final class FlatPricer {

    public static final String REASON_NO_TRAIL = "NO_TRAIL";
    public static final String REASON_NO_PASSAGES = "NO_PASSAGES";
    public static final String REASON_NO_RATE = "NO_RATE";
    public static final String REASON_MIXED_CURRENCY = "MIXED_CURRENCY";
    public static final String DAY_TYPE_ALL = "all_days";

    public record Result(TollEstimate estimate, String facilityId, Map<String, Object> inputs) {
    }

    private FlatPricer() {
    }

    public static Result price(List<PassageDetector.TrailPoint> trail, List<GantrySet> sets, Date start) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("model", "flat");
        inputs.put("thresholdM", PassageDetector.THRESHOLD_M);
        inputs.put("debounceS", PassageDetector.DEBOUNCE_S);
        Map<String, GantrySet> byFacility = new LinkedHashMap<>();
        Map<String, String> cards = new LinkedHashMap<>();
        Map<String, String> osm = new LinkedHashMap<>();
        for (GantrySet s : sets) {
            byFacility.put(s.getFacilityId(), s);
            cards.put(s.getFacilityId(), start != null ? s.cardAt(start) : null);
            osm.put(s.getFacilityId(), s.getOsmBase());
        }
        inputs.put("cardValidFrom", cards);
        inputs.put("osmBase", osm);
        inputs.put("onRampM", PassageDetector.ON_RAMP_M);
        inputs.put("offMainlineM", PassageDetector.OFF_MAINLINE_M);
        for (GantrySet s : sets) {
            if (s.rampsExcluded()) {
                // Ramp plazas are not detected yet for this facility: the estimate is a lower bound.
                inputs.put("rampsNotPriced", true);
            }
        }
        // Until a passage names one, no facility is charged: an unpriced row names none and lists those
        // tested. (Naming the first one tested put Chicago Skyway / Indiana Toll Road trips under the
        // Illinois Tollway and Colorado express-lane trips under E-470 in the 2026-11-10 dry-run.)
        inputs.put("testedFacilities", new ArrayList<>(byFacility.keySet()));
        String fallbackFacility = sets.get(0).getFacilityId();
        if (trail == null || trail.size() < 2) {
            inputs.put("trailPoints", trail == null ? 0 : trail.size());
            return new Result(TollEstimate.unknown(REASON_NO_TRAIL, null, DAY_TYPE_ALL, null, null), null, inputs);
        }
        inputs.put("trailPoints", trail.size());

        List<PassageDetector.Passage> passages = PassageDetector.detect(trail, sets);
        if (passages.isEmpty()) {
            return new Result(TollEstimate.unknown(REASON_NO_PASSAGES, null, DAY_TYPE_ALL, null, null), null, inputs);
        }

        Map<String, BigDecimal> subtotal = new LinkedHashMap<>();
        Map<String, String> firstCard = new LinkedHashMap<>();
        Map<String, Set<String>> cardsUsed = new LinkedHashMap<>();
        List<TollEstimate.Line> lines = new ArrayList<>();
        List<List<Object>> compact = new ArrayList<>();
        for (PassageDetector.Passage p : passages) {
            GantrySet.Point point = p.point();
            String label = point.facilityId() + ":" + point.plaza();
            long offset = start != null ? (p.time().getTime() - start.getTime()) / 1000 : 0;
            compact.add(List.of(label, offset, Math.round(p.distanceM())));
            GantrySet.Rate rate = byFacility.get(point.facilityId()).rateAt(point, p.time());
            if (rate == null) {
                inputs.put("passages", compact);
                inputs.put("noRateFor", label);
                GantrySet owner = byFacility.get(point.facilityId());
                return new Result(TollEstimate.unknown(REASON_NO_RATE, owner.getCurrency(), DAY_TYPE_ALL, null,
                        cards.get(point.facilityId())), point.facilityId(), inputs);
            }
            subtotal.merge(point.facilityId(), rate.amount(), BigDecimal::add);
            firstCard.putIfAbsent(point.facilityId(), rate.cardValidFrom());
            cardsUsed.computeIfAbsent(point.facilityId(), k -> new LinkedHashSet<>()).add(rate.cardValidFrom());
            lines.add(new TollEstimate.Line(label, BigDecimal.ONE, rate.amount(), rate.amount()));
        }
        inputs.put("passages", compact);
        inputs.put("breakdown", subtotal);
        if (cardsUsed.values().stream().anyMatch(used -> used.size() > 1)) {
            inputs.put("cardsUsed", cardsUsed);   // the trip crossed a reprice
        }

        String facility = subtotal.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallbackFacility);
        Set<String> currencies = new LinkedHashSet<>();
        subtotal.keySet().forEach(f -> currencies.add(byFacility.get(f).getCurrency()));
        if (currencies.size() > 1) {
            return new Result(TollEstimate.unknown(REASON_MIXED_CURRENCY, null, DAY_TYPE_ALL, null, null),
                    facility, inputs);
        }
        BigDecimal total = subtotal.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(TollEstimate.priced(total, byFacility.get(facility).getCurrency(), DAY_TYPE_ALL, null,
                firstCard.get(facility), lines), facility, inputs);
    }
}

package org.traccar.tollpricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices a traversal of flat-rate (per-plaza) facilities from the passages its trail made.
 *
 * <p>Each passage is charged by the facility that owns the toll point (decision 23, gap 2 A), so a
 * trip that crosses operators is split correctly; the row's facility is the one with the largest
 * subtotal and {@code breakdown} lists each. No passage means unknown, not zero.
 */
public final class FlatPricer {

    public static final String REASON_NO_TRAIL = "NO_TRAIL";
    public static final String REASON_NO_PASSAGES = "NO_PASSAGES";
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
        Map<String, String> cards = new LinkedHashMap<>();
        Map<String, String> osm = new LinkedHashMap<>();
        for (GantrySet s : sets) {
            cards.put(s.getFacilityId(), s.getCardValidFrom());
            osm.put(s.getFacilityId(), s.getOsmBase());
        }
        inputs.put("cardValidFrom", cards);
        inputs.put("osmBase", osm);
        for (GantrySet s : sets) {
            if (s.rampsExcluded()) {
                // Ramp plazas are not detected yet for this facility: the estimate is a lower bound.
                inputs.put("rampsNotPriced", true);
            }
        }
        String fallbackFacility = sets.get(0).getFacilityId();
        String fallbackCurrency = sets.get(0).getCurrency();
        if (trail == null || trail.size() < 2) {
            inputs.put("trailPoints", trail == null ? 0 : trail.size());
            return new Result(TollEstimate.unknown(REASON_NO_TRAIL, fallbackCurrency, DAY_TYPE_ALL, null,
                    sets.get(0).getCardValidFrom()), fallbackFacility, inputs);
        }
        inputs.put("trailPoints", trail.size());

        List<PassageDetector.Passage> passages = PassageDetector.detect(trail, sets);
        if (passages.isEmpty()) {
            return new Result(TollEstimate.unknown(REASON_NO_PASSAGES, fallbackCurrency, DAY_TYPE_ALL, null,
                    sets.get(0).getCardValidFrom()), fallbackFacility, inputs);
        }

        Map<String, BigDecimal> subtotal = new LinkedHashMap<>();
        Map<String, String> currency = new LinkedHashMap<>();
        List<TollEstimate.Line> lines = new ArrayList<>();
        List<List<Object>> compact = new ArrayList<>();
        for (PassageDetector.Passage p : passages) {
            GantrySet.Point point = p.point();
            subtotal.merge(point.facilityId(), point.rate(), BigDecimal::add);
            for (GantrySet s : sets) {
                if (s.getFacilityId().equals(point.facilityId())) {
                    currency.put(point.facilityId(), s.getCurrency());
                }
            }
            lines.add(new TollEstimate.Line(point.facilityId() + ":" + point.plaza(), BigDecimal.ONE,
                    point.rate(), point.rate()));
            long offset = start != null ? (p.time().getTime() - start.getTime()) / 1000 : 0;
            compact.add(List.of(point.facilityId() + ":" + point.plaza(), offset, Math.round(p.distanceM())));
        }
        inputs.put("passages", compact);
        inputs.put("breakdown", subtotal);

        String facility = subtotal.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallbackFacility);
        if (currency.values().stream().distinct().count() > 1) {
            return new Result(TollEstimate.unknown(REASON_MIXED_CURRENCY, null, DAY_TYPE_ALL, null, null),
                    facility, inputs);
        }
        BigDecimal total = subtotal.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        String cardValidFrom = cards.get(facility);
        return new Result(TollEstimate.priced(total, currency.get(facility), DAY_TYPE_ALL, null, cardValidFrom,
                lines), facility, inputs);
    }
}

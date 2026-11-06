package org.traccar.tollpricing;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prices a traversal of a per-kilometre, zoned facility (407 ETR model).
 *
 * <p>Rules, as published by the operator and as {@code price_407_reference.py} applies them:
 * <ul>
 *   <li>the <b>whole trip</b> is priced at the band in force at <b>entry</b> time (ETR does not split
 *       a trip at band boundaries);</li>
 *   <li>day type is weekend-or-holiday on Saturday, Sunday and the operator's holidays, else weekday;</li>
 *   <li>each zone's km is charged at that zone's rate for the direction of travel;</li>
 *   <li>plus the per-trip toll charge and, for a plate-billed vehicle, the per-trip camera charge.</li>
 * </ul>
 *
 * <p>Arithmetic is exact decimal, rounded once to the cent (half-up) on the distance component and
 * once on the total.
 */
public final class DistanceZonedEstimator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DistanceZonedEstimator() {
    }

    /**
     * @param entryLocal entry time in the facility's own time zone
     * @param direction  as the card names it, e.g. {@code "eastbound"}
     * @param zoneKm     km travelled per zone code, in travel order
     * @param holiday    whether the entry date is one of the operator's holidays
     */
    public static TollEstimate price(DistanceZonedCard card, LocalDateTime entryLocal, String direction,
                                     Map<String, Double> zoneKm, boolean holiday) throws IOException {
        DayOfWeek dow = entryLocal.getDayOfWeek();
        String dayType = holiday || dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
                ? DistanceZonedCard.DAY_WEEKEND_OR_HOLIDAY : DistanceZonedCard.DAY_WEEKDAY;
        String band = card.resolveBand(dayType, entryLocal.toLocalTime());

        if (direction == null) {
            return TollEstimate.unknown(TollEstimate.REASON_UNKNOWN_DIRECTION,
                    card.getCurrency(), dayType, band, card.getValidFrom());
        }
        if (zoneKm == null || zoneKm.isEmpty()) {
            return TollEstimate.unknown(TollEstimate.REASON_NO_ZONES,
                    card.getCurrency(), dayType, band, card.getValidFrom());
        }

        List<TollEstimate.Line> lines = new ArrayList<>();
        BigDecimal distance = BigDecimal.ZERO;
        for (Map.Entry<String, Double> e : zoneKm.entrySet()) {
            BigDecimal rate = card.rate(e.getKey(), direction, dayType, band);
            if (rate == null) {
                return TollEstimate.unknown(TollEstimate.REASON_NO_RATE,
                        card.getCurrency(), dayType, band, card.getValidFrom());
            }
            BigDecimal km = BigDecimal.valueOf(e.getValue());
            BigDecimal amount = km.multiply(rate).divide(HUNDRED);
            distance = distance.add(amount);
            lines.add(new TollEstimate.Line(e.getKey(), km, rate, amount.setScale(2, RoundingMode.HALF_UP)));
        }

        BigDecimal tripCharge = card.perTripFee("Trip Toll Charge");
        BigDecimal cameraCharge = card.perTripFee("Camera Charge");
        lines.add(new TollEstimate.Line("trip_toll_charge", BigDecimal.ONE, tripCharge, tripCharge));
        lines.add(new TollEstimate.Line("camera_charge", BigDecimal.ONE, cameraCharge, cameraCharge));

        BigDecimal total = distance.add(tripCharge).add(cameraCharge).setScale(2, RoundingMode.HALF_UP);
        return TollEstimate.priced(total, card.getCurrency(), dayType, band, card.getValidFrom(), lines);
    }
}

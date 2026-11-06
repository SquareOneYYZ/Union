package org.traccar.tollpricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A per-kilometre, zone-by-zone rate card (407 ETR is the first), read as-is from the sourced JSON
 * that {@code scripts/toll_research/build_rate_cards.py} writes.
 *
 * <p>The card is the operator's published table transcribed, not a normalised model, so this class
 * does the interpreting: band labels such as {@code "9:30 a.m."} become clock times, and per-trip
 * fees are read out of the fee table's prose ({@code "$5.30 per trip"}). Anything it cannot read
 * is an error at load time, never a default - a rate that is not in the card is unknown, not zero.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DistanceZonedCard {

    public static final String DAY_WEEKDAY = "weekday";
    public static final String DAY_WEEKEND_OR_HOLIDAY = "weekend_or_holiday";

    private static final Pattern BAND_LABEL =
            Pattern.compile("^\\s*(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.m\\.\\s*$");
    private static final Pattern PER_TRIP = Pattern.compile("\\$(\\d+(?:\\.\\d+)?) per trip");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Rate {
        @JsonProperty("zone_code")
        private String zoneCode;
        @JsonProperty("direction")
        private String direction;
        @JsonProperty("day_type")
        private String dayType;
        @JsonProperty("band_start_local")
        private String bandStartLocal;
        @JsonProperty("rate_cents_per_km")
        private BigDecimal rateCentsPerKm;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Fee {
        @JsonProperty("name")
        private String name;
        @JsonProperty("without_transponder")
        private String withoutTransponder;
    }

    @JsonProperty("facility")
    private String facility;
    @JsonProperty("currency")
    private String currency;
    @JsonProperty("rate_model_type")
    private String rateModelType;
    @JsonProperty("valid_from")
    private String validFrom;
    @JsonProperty("source_url")
    private String sourceUrl;
    @JsonProperty("band_boundaries_local")
    private Map<String, List<String>> bandBoundariesLocal;
    @JsonProperty("rates")
    private List<Rate> rates;
    @JsonProperty("fees")
    private List<Fee> fees;

    private final Map<String, BigDecimal> rateIndex = new HashMap<>();

    public static DistanceZonedCard load(InputStream in) throws IOException {
        DistanceZonedCard card = new ObjectMapper().readValue(in, DistanceZonedCard.class);
        card.validate();
        return card;
    }

    public static DistanceZonedCard loadResource(String name) throws IOException {
        try (InputStream in = DistanceZonedCard.class.getResourceAsStream("/toll-pricing/cards/" + name)) {
            if (in == null) {
                throw new IOException("rate card not on classpath: " + name);
            }
            return load(in);
        }
    }

    private void validate() throws IOException {
        if (!"distance_zoned".equals(rateModelType)) {
            throw new IOException("not a distance_zoned card: " + rateModelType);
        }
        if (currency == null || validFrom == null || sourceUrl == null) {
            throw new IOException("card lacks currency, valid_from or source_url");
        }
        for (Map.Entry<String, List<String>> e : bandBoundariesLocal.entrySet()) {
            for (String label : e.getValue()) {
                parseBand(label);
            }
        }
        for (Rate r : rates) {
            if (r.rateCentsPerKm == null) {
                throw new IOException("rate row without a rate: " + r.zoneCode);
            }
            rateIndex.put(key(r.zoneCode, r.direction, r.dayType, r.bandStartLocal), r.rateCentsPerKm);
        }
        perTripFee("Trip Toll Charge");
        perTripFee("Camera Charge");
    }

    private static String key(String zone, String direction, String dayType, String band) {
        return zone + '|' + direction + '|' + dayType + '|' + band;
    }

    /** {@code "9:30 a.m."} to 09:30, {@code "6 p.m."} to 18:00. */
    static LocalTime parseBand(String label) throws IOException {
        Matcher m = BAND_LABEL.matcher(label);
        if (!m.matches()) {
            throw new IOException("unreadable band label: " + label);
        }
        int hour = Integer.parseInt(m.group(1)) % 12;
        if (m.group(3).equals("p")) {
            hour += 12;
        }
        int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        return LocalTime.of(hour, minute);
    }

    /**
     * The band in force at {@code time}. Before the first band of the day, the previous evening's
     * last band is still running, as on the operator's chart.
     */
    public String resolveBand(String dayType, LocalTime time) {
        List<String> labels = bandBoundariesLocal.get(dayType);
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        String chosen = null;
        LocalTime chosenStart = null;
        String latest = null;
        LocalTime latestStart = null;
        for (String label : labels) {
            LocalTime start;
            try {
                start = parseBand(label);
            } catch (IOException e) {
                throw new IllegalStateException(e); // validated at load
            }
            if (latestStart == null || start.isAfter(latestStart)) {
                latest = label;
                latestStart = start;
            }
            if (!time.isBefore(start) && (chosenStart == null || start.isAfter(chosenStart))) {
                chosen = label;
                chosenStart = start;
            }
        }
        return chosen != null ? chosen : latest;
    }

    /** Cents per km, or {@code null} when the card has no such cell. */
    public BigDecimal rate(String zone, String direction, String dayType, String band) {
        return rateIndex.get(key(zone, direction, dayType, band));
    }

    /** A per-trip fee for a plate-billed (no transponder) vehicle, read from the fee table. */
    public BigDecimal perTripFee(String namePrefix) throws IOException {
        for (Fee fee : fees) {
            if (fee.name != null && fee.name.startsWith(namePrefix)) {
                Matcher m = PER_TRIP.matcher(fee.withoutTransponder == null ? "" : fee.withoutTransponder);
                if (m.find()) {
                    return new BigDecimal(m.group(1));
                }
                throw new IOException("fee not per trip: " + namePrefix + " = " + fee.withoutTransponder);
            }
        }
        throw new IOException("fee missing from card: " + namePrefix);
    }

    public String getFacility() {
        return facility;
    }

    public String getCurrency() {
        return currency;
    }

    public String getValidFrom() {
        return validFrom;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public List<String> zoneCodes() {
        List<String> zones = new ArrayList<>();
        for (Rate r : rates) {
            if (!zones.contains(r.zoneCode)) {
                zones.add(r.zoneCode);
            }
        }
        return zones;
    }
}

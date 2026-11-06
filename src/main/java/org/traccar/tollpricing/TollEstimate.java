package org.traccar.tollpricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One priced traversal. {@code amount} is {@code null} when the price is unknown, and then
 * {@code reason} says why: an unknown is never reported as zero.
 */
public final class TollEstimate {

    /** Bumped whenever a rule that changes a number changes, so old rows can be told apart. */
    public static final int CALC_VERSION = 1;

    public static final String REASON_NO_RATE = "NO_RATE";
    public static final String REASON_UNKNOWN_DIRECTION = "UNKNOWN_DIRECTION";
    public static final String REASON_NO_ZONES = "NO_ZONES";

    public record Line(String item, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    private final BigDecimal amount;
    private final String currency;
    private final String reason;
    private final String dayType;
    private final String band;
    private final String cardValidFrom;
    private final List<Line> lines;

    private TollEstimate(BigDecimal amount, String currency, String reason, String dayType, String band,
                         String cardValidFrom, List<Line> lines) {
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.dayType = dayType;
        this.band = band;
        this.cardValidFrom = cardValidFrom;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    static TollEstimate priced(BigDecimal amount, String currency, String dayType, String band,
                               String cardValidFrom, List<Line> lines) {
        return new TollEstimate(amount, currency, null, dayType, band, cardValidFrom, lines);
    }

    static TollEstimate unknown(String reason, String currency, String dayType, String band, String cardValidFrom) {
        return new TollEstimate(null, currency, reason, dayType, band, cardValidFrom, List.of());
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReason() {
        return reason;
    }

    public String getDayType() {
        return dayType;
    }

    public String getBand() {
        return band;
    }

    public String getCardValidFrom() {
        return cardValidFrom;
    }

    public List<Line> getLines() {
        return lines;
    }

    public int getCalcVersion() {
        return CALC_VERSION;
    }
}

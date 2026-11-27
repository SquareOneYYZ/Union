package org.traccar.tollpricing;

import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;

import java.time.LocalDate;
import java.time.Year;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The holidays one toll operator prices as holidays.
 *
 * <p>Jollyday computes the dates (Easter, nth weekday, ...); the facility registry decides which
 * of the jurisdiction's holidays the operator honours, by key. Operators never inherit a full
 * public-holiday list: Ontario has bank holidays (Civic Holiday, Easter Monday) the 407 does not
 * price as holidays. The holiday's actual date is used, not a substitute weekday.
 */
public final class OperatorHolidays {

    private final HolidayManager manager;
    private final String subdivision;
    private final Set<String> keys;
    private final Map<Integer, Set<LocalDate>> byYear = new ConcurrentHashMap<>();

    /**
     * @param calendar    Jollyday calendar code, e.g. {@code "ca"}
     * @param subdivision ISO 3166-2 subdivision as Jollyday names it, e.g. {@code "on"}
     * @param keys        Jollyday properties keys the operator honours
     * @throws IllegalArgumentException when a key matches nothing, so a typo cannot silently drop a holiday
     */
    public OperatorHolidays(String calendar, String subdivision, Set<String> keys) {
        this.manager = HolidayManager.getInstance(ManagerParameters.create(calendar));
        this.subdivision = subdivision;
        this.keys = Set.copyOf(keys);
        Set<String> seen = new HashSet<>();
        for (Holiday h : manager.getHolidays(Year.of(2026), subdivision)) {
            seen.add(h.getPropertiesKey());
        }
        Set<String> unknown = new TreeSet<>(this.keys);
        unknown.removeAll(seen);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("holiday keys not in " + calendar + "/" + subdivision + ": " + unknown);
        }
    }

    public boolean isHoliday(LocalDate date) {
        return byYear.computeIfAbsent(date.getYear(), year -> {
            Set<LocalDate> dates = new HashSet<>();
            for (Holiday h : manager.getHolidays(Year.of(year), subdivision)) {
                if (keys.contains(h.getPropertiesKey())) {
                    dates.add(h.getActualDate());
                }
            }
            return dates;
        }).contains(date);
    }
}

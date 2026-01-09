
package org.traccar.helper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public final class ReportPeriodUtil {

    private ReportPeriodUtil() {
    }

    public static String detectPeriod(Date from, Date to) {
        if (from == null || to == null) {
            return "Custom";
        }

        // Convert to LocalDate for easier comparison
        LocalDate fromDate = Instant.ofEpochMilli(from.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        LocalDate toDate = Instant.ofEpochMilli(to.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        LocalDate today = LocalDate.now();

        if (fromDate.equals(today) && toDate.equals(today)) {
            return "Today";
        }
        LocalDate yesterday = today.minusDays(1);
        if (fromDate.equals(yesterday) && toDate.equals(yesterday)) {
            return "Yesterday";
        }
        LocalDate weekStart = today.with(java.time.DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);
        if (isWithinRange(fromDate, toDate, weekStart, weekEnd)) {
            return "This Week";
        }
        LocalDate prevWeekStart = weekStart.minusWeeks(1);
        LocalDate prevWeekEnd = prevWeekStart.plusDays(6);
        if (isWithinRange(fromDate, toDate, prevWeekStart, prevWeekEnd)) {
            return "Previous Week";
        }
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        if (isWithinRange(fromDate, toDate, monthStart, monthEnd)) {
            return "This Month";
        }
        LocalDate prevMonthStart = monthStart.minusMonths(1);
        LocalDate prevMonthEnd = prevMonthStart.withDayOfMonth(prevMonthStart.lengthOfMonth());
        if (isWithinRange(fromDate, toDate, prevMonthStart, prevMonthEnd)) {
            return "Previous Month";
        }
        return "Custom";
    }

    private static boolean isWithinRange(LocalDate from, LocalDate to, LocalDate expectedStart, LocalDate expectedEnd) {
        long fromDiff = Math.abs(ChronoUnit.DAYS.between(from, expectedStart));
        long toDiff = Math.abs(ChronoUnit.DAYS.between(to, expectedEnd));
        return fromDiff <= 1 && toDiff <= 1;
    }

}

package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.web.ApiException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;

/** ISO-8601 week arithmetic (Monday to Sunday; week 1 contains the first Thursday of the year). */
final class IsoWeeks {

    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2200;

    private IsoWeeks() {
    }

    /** @return number of ISO weeks (52 or 53) in the ISO week-based year */
    static int weeksIn(int isoYear) {
        return LocalDate.of(isoYear, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** @throws ApiException 400 for a year or week that does not exist */
    static void validate(int isoYear, int isoWeek) {
        if (isoYear < MIN_YEAR || isoYear > MAX_YEAR || isoWeek < 1 || isoWeek > weeksIn(isoYear)) {
            throw ApiException.badRequest("INVALID_WEEK", "This week does not exist. Choose a valid ISO year and week.");
        }
    }

    /** Monday of the week. */
    static LocalDate start(int isoYear, int isoWeek) {
        validate(isoYear, isoWeek);
        return LocalDate.of(isoYear, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, isoWeek)
                .with(DayOfWeek.MONDAY);
    }

    /** The Monday after the week (exclusive end). */
    static LocalDate endExclusive(int isoYear, int isoWeek) {
        return start(isoYear, isoWeek).plusDays(7);
    }
}

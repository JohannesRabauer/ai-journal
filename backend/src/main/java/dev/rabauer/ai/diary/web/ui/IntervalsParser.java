package dev.rabauer.ai.diary.web.ui;

import java.util.ArrayList;
import java.util.List;

/** Parses and validates review intervals typed as "1, 3, 7, 14, 30": strictly ascending whole days in 1..365. */
public final class IntervalsParser {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 365;
    public static final int MAX_COUNT = 10;

    private IntervalsParser() {
    }

    /** Either {@code values} (error == null) or an i18n {@code errorKey}. */
    public record Result(List<Integer> values, String errorKey) {
        public boolean valid() {
            return errorKey == null;
        }
    }

    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return error("settings.intervals.error.empty");
        }
        List<Integer> values = new ArrayList<>();
        for (String part : text.split("[,;\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int v;
            try {
                v = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return error("settings.intervals.error.format");
            }
            if (v < MIN_DAYS || v > MAX_DAYS) {
                return error("settings.intervals.error.range");
            }
            if (!values.isEmpty() && v <= values.get(values.size() - 1)) {
                return error("settings.intervals.error.order");
            }
            values.add(v);
        }
        if (values.isEmpty()) {
            return error("settings.intervals.error.empty");
        }
        if (values.size() > MAX_COUNT) {
            return error("settings.intervals.error.count");
        }
        return new Result(List.copyOf(values), null);
    }

    public static String format(List<Integer> values) {
        return values == null ? "" : String.join(", ", values.stream().map(String::valueOf).toList());
    }

    private static Result error(String key) {
        return new Result(null, key);
    }
}

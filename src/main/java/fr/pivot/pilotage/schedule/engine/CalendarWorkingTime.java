package fr.pivot.pilotage.schedule.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the {@code pilotage.calendar.working_time} JSONB payload (EN22.1b), converting it into
 * the whole-hour intra-day ranges the {@link WorkingCalendar} consumes.
 *
 * <p>Convention (documented here as the single source of truth for the engine): the payload is a
 * JSON object with a {@code "ranges"} array of {@code ["HH:MM","HH:MM"]} pairs, e.g.
 * {@code {"ranges":[["09:00","12:00"],["13:00","17:00"]]}}. An empty object {@code {}} means "use a
 * default full business day" and is handled by the caller. Times must be whole-hour aligned (the
 * engine granularity); minutes other than {@code 00} are rejected.
 *
 * <p>Kept dependency-free (hand parser) so the pure-core engine package needs no JSON library on its
 * classpath; the service passes the already-persisted string.
 */
public final class CalendarWorkingTime {

    private CalendarWorkingTime() {
    }

    /**
     * Parses a working-time JSON payload into whole-hour ranges (minutes from midnight).
     *
     * @param json         the JSONB string (may be {@code null}, {@code ""} or {@code "{}"})
     * @param defaultRanges the ranges to return when the payload carries no explicit ranges
     * @return the parsed ranges, or {@code defaultRanges} when none are present
     * @throws IllegalArgumentException if a time is malformed or not whole-hour aligned
     */
    public static List<int[]> parse(final String json, final List<int[]> defaultRanges) {
        if (json == null || json.isBlank() || json.replaceAll("\\s", "").equals("{}")) {
            return defaultRanges;
        }
        final int idx = json.indexOf("\"ranges\"");
        if (idx < 0) {
            return defaultRanges;
        }
        final int open = json.indexOf('[', idx);
        final int close = json.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return defaultRanges;
        }
        final String body = json.substring(open + 1, close);
        final List<int[]> ranges = new ArrayList<>();
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[\\s*\"(\\d{2}):(\\d{2})\"\\s*,\\s*\"(\\d{2}):(\\d{2})\"\\s*\\]")
                .matcher(body);
        while (m.find()) {
            final int startMin = toMinutes(m.group(1), m.group(2));
            final int endMin = toMinutes(m.group(3), m.group(4));
            ranges.add(new int[] {startMin, endMin});
        }
        return ranges.isEmpty() ? defaultRanges : ranges;
    }

    private static int toMinutes(final String hh, final String mm) {
        final int h = Integer.parseInt(hh);
        final int min = Integer.parseInt(mm);
        if (min != 0) {
            throw new IllegalArgumentException(
                    "working time must be whole-hour aligned (minutes=00): " + hh + ":" + mm);
        }
        if (h < 0 || h > 24) {
            throw new IllegalArgumentException("hour out of range: " + hh);
        }
        return h * 60;
    }
}

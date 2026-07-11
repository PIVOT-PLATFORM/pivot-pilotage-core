package fr.pivot.pilotage.schedule.engine;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A working-time calendar (EN22.1b) — the immutable, pure-core projection of a {@code pilotage}
 * calendar plus its exceptions onto a monotonic <em>worked-minute axis</em>.
 *
 * <p><strong>Granularity decision (maintainer, EN22.1b): the engine reasons at the WORKED HOUR.</strong>
 * Working ranges are whole hours, durations and lags are snapped up to the next whole worked hour,
 * and the axis therefore advances in 60-minute increments. Storage stays in minutes (one hour =
 * 60 minutes) — the calendar is the single place that maps between wall-clock {@link Instant}s and
 * cumulative worked minutes.
 *
 * <p>The calendar exposes two total, deterministic operations:
 * <ul>
 *   <li>{@link #advance(Instant, long)} — given an anchor instant and a number of worked minutes,
 *       return the wall-clock instant reached after consuming that much working time (forward
 *       pass);</li>
 *   <li>{@link #retreat(Instant, long)} — the symmetric backward operation used by the late pass.</li>
 * </ul>
 *
 * <p>All computation is in UTC ({@link ZoneOffset#UTC}); the model is calendar-day + intra-day
 * working ranges. No {@code now()} or randomness is ever consulted — the calendar is a pure
 * function of its construction arguments, satisfying the determinism invariant D1.
 */
public final class WorkingCalendar {

    /** Minutes in one worked hour — the canonical granularity unit. */
    public static final long MINUTES_PER_HOUR = 60L;

    /** Maximum number of days scanned before giving up (guards against an empty calendar). */
    private static final int MAX_SCAN_DAYS = 3660;

    private final long calendarId;
    private final boolean[] workingDay;
    private final List<int[]> defaultRanges;
    private final Map<LocalDate, DayExceptionModel> exceptions;

    /**
     * Builds a working calendar.
     *
     * @param calendarId      the source calendar id (identity / diagnostics)
     * @param workingDaysMask Mon..Sun bitmask (bit 0 = Monday .. bit 6 = Sunday) of working days
     * @param defaultRanges   default intra-day working ranges (minutes from midnight,
     *                        {@code [startInclusive, endExclusive)}), whole-hour aligned
     * @param exceptions      per-date exceptions overriding the default day model
     * @throws NullPointerException     if {@code defaultRanges} or {@code exceptions} is null
     * @throws IllegalArgumentException if a range is not whole-hour aligned or is malformed
     */
    public WorkingCalendar(final long calendarId, final int workingDaysMask,
            final List<int[]> defaultRanges, final Map<LocalDate, DayExceptionModel> exceptions) {
        this.calendarId = calendarId;
        this.workingDay = new boolean[7];
        for (int i = 0; i < 7; i++) {
            this.workingDay[i] = (workingDaysMask & (1 << i)) != 0;
        }
        this.defaultRanges = normalize(Objects.requireNonNull(defaultRanges, "defaultRanges"));
        this.exceptions = new TreeMap<>(Objects.requireNonNull(exceptions, "exceptions"));
    }

    private static List<int[]> normalize(final List<int[]> ranges) {
        for (final int[] r : ranges) {
            if (r.length != 2 || r[0] < 0 || r[1] > 1440 || r[0] >= r[1]) {
                throw new IllegalArgumentException("invalid working range " + java.util.Arrays.toString(r));
            }
            if (r[0] % 60 != 0 || r[1] % 60 != 0) {
                throw new IllegalArgumentException(
                        "working ranges must be whole-hour aligned: " + java.util.Arrays.toString(r));
            }
        }
        // Defensive copy so the calendar stays immutable.
        return ranges.stream().map(int[]::clone).toList();
    }

    /**
     * Returns the source calendar id.
     *
     * @return the calendar id
     */
    public long calendarId() {
        return calendarId;
    }

    /**
     * Returns the intra-day working ranges effective for the given date (exception-aware).
     *
     * @param date the calendar date
     * @return the working ranges (possibly empty for a non-working day)
     */
    private List<int[]> rangesFor(final LocalDate date) {
        final DayExceptionModel ex = exceptions.get(date);
        if (ex != null) {
            return ex.working() ? ex.ranges(defaultRanges) : List.of();
        }
        final boolean working = workingDay[date.getDayOfWeek().getValue() - 1];
        return working ? defaultRanges : List.of();
    }

    /**
     * Returns whether the given date is a working day (exception-aware).
     *
     * @param date the calendar date
     * @return {@code true} if any working time exists on that date
     */
    public boolean isWorkingDay(final LocalDate date) {
        return !rangesFor(date).isEmpty();
    }

    /**
     * Snaps an arbitrary instant forward to the next start of working time (or leaves it untouched
     * if it already sits inside a working range), yielding the earliest valid working instant at or
     * after {@code from}.
     *
     * @param from the anchor instant
     * @return the snapped working instant
     */
    public Instant snapForward(final Instant from) {
        LocalDateTime cursor = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        for (int i = 0; i < MAX_SCAN_DAYS; i++) {
            final LocalDate day = cursor.toLocalDate();
            final int minuteOfDay = cursor.toLocalTime().getHour() * 60 + cursor.toLocalTime().getMinute();
            for (final int[] r : rangesFor(day)) {
                if (minuteOfDay < r[1]) {
                    final int start = Math.max(minuteOfDay, r[0]);
                    return day.atStartOfDay(ZoneOffset.UTC).plusMinutes(start).toInstant();
                }
            }
            cursor = day.plusDays(1).atStartOfDay();
        }
        throw new IllegalStateException("calendar " + calendarId + " has no working time within horizon");
    }

    /**
     * Advances from an anchor instant by a number of worked minutes (forward pass). The anchor is
     * first snapped forward to valid working time; then {@code minutes} of working time are
     * consumed. A zero duration returns the snapped anchor (milestone semantics).
     *
     * @param from    the anchor instant
     * @param minutes worked minutes to consume ({@code >= 0})
     * @return the wall-clock instant reached
     * @throws IllegalArgumentException if {@code minutes} is negative
     */
    public Instant advance(final Instant from, final long minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("minutes must be >= 0, was " + minutes);
        }
        Instant cursor = snapForward(from);
        long remaining = minutes;
        if (remaining == 0) {
            return cursor;
        }
        for (int guard = 0; guard < MAX_SCAN_DAYS * 24; guard++) {
            final LocalDateTime dt = LocalDateTime.ofInstant(cursor, ZoneOffset.UTC);
            final LocalDate day = dt.toLocalDate();
            final int minuteOfDay = dt.getHour() * 60 + dt.getMinute();
            for (final int[] r : rangesFor(day)) {
                if (minuteOfDay >= r[0] && minuteOfDay < r[1]) {
                    final long avail = r[1] - minuteOfDay;
                    if (remaining <= avail) {
                        return day.atStartOfDay(ZoneOffset.UTC).plusMinutes(minuteOfDay + remaining).toInstant();
                    }
                    remaining -= avail;
                    cursor = day.atStartOfDay(ZoneOffset.UTC).plusMinutes(r[1]).toInstant();
                    cursor = snapForward(cursor);
                    break;
                }
            }
        }
        throw new IllegalStateException("calendar " + calendarId + " exhausted advancing " + minutes + " min");
    }

    /**
     * Snaps an arbitrary instant backward to the end of the latest working range at or before
     * {@code from} (or leaves it untouched if it sits on a working-range boundary).
     *
     * @param from the anchor instant
     * @return the snapped working instant
     */
    public Instant snapBackward(final Instant from) {
        LocalDateTime cursor = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        for (int i = 0; i < MAX_SCAN_DAYS; i++) {
            final LocalDate day = cursor.toLocalDate();
            final int minuteOfDay = cursor.toLocalTime().getHour() * 60 + cursor.toLocalTime().getMinute();
            final List<int[]> ranges = rangesFor(day);
            for (int j = ranges.size() - 1; j >= 0; j--) {
                final int[] r = ranges.get(j);
                if (minuteOfDay > r[0]) {
                    final int end = Math.min(minuteOfDay, r[1]);
                    return day.atStartOfDay(ZoneOffset.UTC).plusMinutes(end).toInstant();
                }
            }
            cursor = day.minusDays(1).atTime(LocalTime.of(23, 59));
        }
        throw new IllegalStateException("calendar " + calendarId + " has no working time within horizon");
    }

    /**
     * Retreats from an anchor instant by a number of worked minutes (backward pass). The anchor is
     * first snapped backward to valid working time; then {@code minutes} of working time are
     * consumed in reverse. A zero duration returns the snapped anchor.
     *
     * @param from    the anchor instant
     * @param minutes worked minutes to consume in reverse ({@code >= 0})
     * @return the wall-clock instant reached
     * @throws IllegalArgumentException if {@code minutes} is negative
     */
    public Instant retreat(final Instant from, final long minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("minutes must be >= 0, was " + minutes);
        }
        Instant cursor = snapBackward(from);
        long remaining = minutes;
        if (remaining == 0) {
            return cursor;
        }
        for (int guard = 0; guard < MAX_SCAN_DAYS * 24; guard++) {
            final LocalDateTime dt = LocalDateTime.ofInstant(cursor, ZoneOffset.UTC);
            final LocalDate day = dt.toLocalDate();
            final int minuteOfDay = dt.getHour() * 60 + dt.getMinute();
            final List<int[]> ranges = rangesFor(day);
            for (int j = ranges.size() - 1; j >= 0; j--) {
                final int[] r = ranges.get(j);
                if (minuteOfDay > r[0] && minuteOfDay <= r[1]) {
                    final long avail = minuteOfDay - r[0];
                    if (remaining <= avail) {
                        return day.atStartOfDay(ZoneOffset.UTC).plusMinutes(minuteOfDay - remaining).toInstant();
                    }
                    remaining -= avail;
                    cursor = day.atStartOfDay(ZoneOffset.UTC).plusMinutes(r[0]).toInstant();
                    cursor = snapBackward(cursor);
                    break;
                }
            }
        }
        throw new IllegalStateException("calendar " + calendarId + " exhausted retreating " + minutes + " min");
    }

    /**
     * Counts the worked minutes strictly between two instants ({@code from <= to}); used to compute
     * free/total float in worked minutes. Both bounds are snapped into working time.
     *
     * @param from the earlier instant
     * @param to   the later instant
     * @return the worked minutes in {@code [from, to)}, never negative
     */
    public long workedMinutesBetween(final Instant from, final Instant to) {
        if (!to.isAfter(from)) {
            return 0L;
        }
        Instant lo = snapForward(from);
        final Instant hi = to;
        long total = 0L;
        for (int guard = 0; guard < MAX_SCAN_DAYS * 24 && lo.isBefore(hi); guard++) {
            final LocalDateTime dt = LocalDateTime.ofInstant(lo, ZoneOffset.UTC);
            final LocalDate day = dt.toLocalDate();
            final int minuteOfDay = dt.getHour() * 60 + dt.getMinute();
            boolean progressed = false;
            for (final int[] r : rangesFor(day)) {
                if (minuteOfDay >= r[0] && minuteOfDay < r[1]) {
                    final Instant rangeEnd = day.atStartOfDay(ZoneOffset.UTC).plusMinutes(r[1]).toInstant();
                    final Instant segEnd = rangeEnd.isBefore(hi) ? rangeEnd : hi;
                    total += Duration.between(lo, segEnd).toMinutes();
                    lo = rangeEnd;
                    progressed = true;
                    break;
                }
            }
            if (!progressed) {
                lo = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            } else if (lo.isBefore(hi)) {
                lo = snapForward(lo);
            }
        }
        return total;
    }

    /**
     * Rounds a raw minute amount up to a whole worked hour — the engine granularity.
     *
     * @param minutes the raw minute amount ({@code >= 0})
     * @return {@code minutes} rounded up to the next multiple of 60
     */
    public static long snapToHour(final long minutes) {
        if (minutes <= 0) {
            return 0L;
        }
        final long rem = minutes % MINUTES_PER_HOUR;
        return rem == 0 ? minutes : minutes + (MINUTES_PER_HOUR - rem);
    }

    /** Convenience factory for a Mon-Fri 8h/day (09:00-17:00) calendar used widely in tests. */
    private static final int[] NINE_TO_FIVE = {9 * 60, 17 * 60};

    /**
     * Builds a standard Monday-Friday, 09:00-17:00 (8 worked hours/day) calendar with no
     * exceptions — a convenience for callers and tests.
     *
     * @param calendarId the calendar id to carry
     * @return the standard business calendar
     */
    public static WorkingCalendar standardBusiness(final long calendarId) {
        final int monToFri = 0b0011111; // bits 0..4 = Mon..Fri
        return new WorkingCalendar(calendarId, monToFri,
                List.of(NINE_TO_FIVE.clone()), Map.of());
    }

    /**
     * Per-date exception model: whether the day is worked and, if so, its specific ranges (or the
     * calendar default when {@code specificRanges} is empty).
     *
     * @param working        whether the day is worked
     * @param specificRanges specific whole-hour ranges when worked; empty ⇒ use default
     */
    public record DayExceptionModel(boolean working, List<int[]> specificRanges) {

        /**
         * Canonical constructor with a defensive copy of the ranges.
         *
         * @param working        whether the day is worked
         * @param specificRanges specific ranges (defensively copied)
         */
        public DayExceptionModel {
            specificRanges = specificRanges == null
                    ? List.of() : specificRanges.stream().map(int[]::clone).toList();
        }

        /**
         * Accessor returning a deep defensive copy: the stored list is immutable but its
         * {@code int[]} elements are mutable arrays — clone them on read so callers cannot
         * mutate the internal ranges (EI_EXPOSE_REP).
         *
         * @return a deep copy of the specific whole-hour ranges
         */
        @Override
        public List<int[]> specificRanges() {
            return specificRanges.stream().map(int[]::clone).toList();
        }

        List<int[]> ranges(final List<int[]> defaults) {
            return specificRanges.isEmpty() ? defaults : specificRanges;
        }
    }

    /**
     * Returns the {@link DayOfWeek}s considered working by this calendar (diagnostics/tests).
     *
     * @return an unmodifiable list of working days of week
     */
    public List<DayOfWeek> workingDaysOfWeek() {
        final java.util.List<DayOfWeek> result = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if (workingDay[i]) {
                result.add(DayOfWeek.of(i + 1));
            }
        }
        return List.copyOf(result);
    }
}

package fr.pivot.pilotage.schedule.engine;

import java.time.Instant;

/**
 * A per-task diff entry in a {@link ScheduleDiff} (EN22.1b) — only the fields that actually changed
 * are non-null (a diff, not a snapshot). {@code null} for a date field means "unchanged"; the
 * {@code criticalChanged} flag disambiguates a critical flag change (whose new value is
 * {@link #critical}).
 *
 * @param taskId          the changed task
 * @param earlyStart      new ES if it changed, else {@code null}
 * @param earlyFinish     new EF if it changed, else {@code null}
 * @param lateStart       new LS if it changed, else {@code null}
 * @param lateFinish      new LF if it changed, else {@code null}
 * @param totalFloatMinutes new total float if it changed, else {@code null}
 * @param criticalChanged whether the critical flag changed
 * @param critical        the new critical flag value (meaningful only if {@code criticalChanged})
 */
public record TaskPatch(
        long taskId,
        Instant earlyStart,
        Instant earlyFinish,
        Instant lateStart,
        Instant lateFinish,
        Long totalFloatMinutes,
        boolean criticalChanged,
        boolean critical) {

    /**
     * Builds the diff between a previous and a new per-task schedule, or {@code null} if nothing
     * changed.
     *
     * @param before the previous schedule (may be {@code null} for a newly added task)
     * @param after  the new schedule
     * @return the patch, or {@code null} if identical
     */
    public static TaskPatch diff(final TaskSchedule before, final TaskSchedule after) {
        if (before == null) {
            return new TaskPatch(after.taskId(), after.earlyStart(), after.earlyFinish(),
                    after.lateStart(), after.lateFinish(), after.totalFloatMinutes(),
                    true, after.critical());
        }
        final boolean esCh = !before.earlyStart().equals(after.earlyStart());
        final boolean efCh = !before.earlyFinish().equals(after.earlyFinish());
        final boolean lsCh = !before.lateStart().equals(after.lateStart());
        final boolean lfCh = !before.lateFinish().equals(after.lateFinish());
        final boolean tfCh = before.totalFloatMinutes() != after.totalFloatMinutes();
        final boolean crCh = before.critical() != after.critical();
        if (!esCh && !efCh && !lsCh && !lfCh && !tfCh && !crCh) {
            return null;
        }
        return new TaskPatch(after.taskId(),
                esCh ? after.earlyStart() : null,
                efCh ? after.earlyFinish() : null,
                lsCh ? after.lateStart() : null,
                lfCh ? after.lateFinish() : null,
                tfCh ? after.totalFloatMinutes() : null,
                crCh, after.critical());
    }
}

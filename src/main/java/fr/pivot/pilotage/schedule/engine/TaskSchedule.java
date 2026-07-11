package fr.pivot.pilotage.schedule.engine;

import java.time.Instant;

/**
 * Per-task CPM result (EN22.1b): early/late dates, float in worked minutes and the critical flag.
 *
 * @param taskId       the task id
 * @param earlyStart   earliest start (forward pass)
 * @param earlyFinish  earliest finish (forward pass)
 * @param lateStart    latest start (backward pass)
 * @param lateFinish   latest finish (backward pass)
 * @param totalFloatMinutes total float in worked minutes ({@code LS - ES})
 * @param freeFloatMinutes  free float in worked minutes (bounded at 0)
 * @param critical     whether the task is on the critical path ({@code totalFloat <= epsilon})
 * @param aggregated   whether these values were aggregated from children (SUMMARY)
 */
public record TaskSchedule(
        long taskId,
        Instant earlyStart,
        Instant earlyFinish,
        Instant lateStart,
        Instant lateFinish,
        long totalFloatMinutes,
        long freeFloatMinutes,
        boolean critical,
        boolean aggregated) {
}

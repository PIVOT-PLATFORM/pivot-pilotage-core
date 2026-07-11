package fr.pivot.pilotage.schedule.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of a full {@link ScheduleEngine#schedule(ScheduleInput)} run (EN22.1b, frozen contract §b).
 *
 * <p>Determinism (D1): for the same input the result is value-identical — {@link #computedAt} is a
 * pure function of the input (its {@code dataDate}), never {@code now()}, and {@link #inputHash}
 * is a stable content hash used by {@code reSchedule} to detect a stale base version.
 *
 * @param tasks          per-task CPM results, keyed by task id (leaves + aggregated summaries)
 * @param criticalPath   the critical task ids in canonical tie-break order
 * @param warnings       typed warnings (constraint conflicts, missed deadlines, negative float)
 * @param variances      manual-vs-auto variances for MANUAL tasks
 * @param computedAt     a deterministic timestamp derived from the input (the data date)
 * @param scheduleVersion monotonic version (0 for a fresh full schedule)
 * @param inputHash      a stable content hash of the effective input
 */
public record ScheduleResult(
        Map<Long, TaskSchedule> tasks,
        List<Long> criticalPath,
        List<SchedulingWarning> warnings,
        List<ManualVariance> variances,
        Instant computedAt,
        long scheduleVersion,
        long inputHash) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies to keep the result immutable.
     *
     * @throws NullPointerException if any collection is null
     */
    public ScheduleResult {
        tasks = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(tasks, "tasks")));
        criticalPath = List.copyOf(Objects.requireNonNull(criticalPath, "criticalPath"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        variances = List.copyOf(Objects.requireNonNull(variances, "variances"));
    }

    /**
     * Returns the CPM result for a task.
     *
     * @param taskId the task id
     * @return the {@link TaskSchedule}, or {@code null} if the task is unknown
     */
    public TaskSchedule task(final long taskId) {
        return tasks.get(taskId);
    }

    /**
     * Returns the critical path as a sorted, immutable list (already canonical) — convenience for
     * change detection in {@code reSchedule}.
     *
     * @return the critical path
     */
    public List<Long> criticalPathSorted() {
        return new ArrayList<>(criticalPath);
    }
}

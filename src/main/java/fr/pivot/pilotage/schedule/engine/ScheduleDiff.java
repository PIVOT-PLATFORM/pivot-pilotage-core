package fr.pivot.pilotage.schedule.engine;

import java.util.List;
import java.util.Objects;

/**
 * The DIFF returned by {@link ScheduleEngine}'s incremental recompute (EN22.1b, frozen contract §b):
 * a per-task patch plus the new critical path <em>only when it changed</em>, the affected count and
 * the new version. Never a full snapshot.
 *
 * @param patches         per-task diffs (only changed tasks; empty ⇒ idempotent no-op)
 * @param newCriticalPath the new critical path, or {@code null} if it did not change
 * @param removed         ids of tasks removed by the delta
 * @param warnings        typed warnings raised while recomputing
 * @param variances       manual-vs-auto variances after the delta
 * @param baseVersion     the version this delta was applied against
 * @param scheduleVersion the produced version ({@code baseVersion + 1} on success)
 */
public record ScheduleDiff(
        List<TaskPatch> patches,
        List<Long> newCriticalPath,
        List<Long> removed,
        List<SchedulingWarning> warnings,
        List<ManualVariance> variances,
        long baseVersion,
        long scheduleVersion) {

    /**
     * Canonical constructor taking defensive copies; {@code newCriticalPath} stays nullable to carry
     * the "changed?" signal.
     *
     * @throws NullPointerException if a required collection is null
     */
    public ScheduleDiff {
        patches = List.copyOf(Objects.requireNonNull(patches, "patches"));
        removed = List.copyOf(Objects.requireNonNull(removed, "removed"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        variances = List.copyOf(Objects.requireNonNull(variances, "variances"));
        newCriticalPath = newCriticalPath == null ? null : List.copyOf(newCriticalPath);
    }

    /**
     * Returns the number of affected tasks (patched + removed).
     *
     * @return the affected count
     */
    public int affectedCount() {
        return patches.size() + removed.size();
    }

    /**
     * Returns whether the patch is empty (idempotent recompute, D3).
     *
     * @return {@code true} if no task changed and none was removed
     */
    public boolean isEmptyPatch() {
        return patches.isEmpty() && removed.isEmpty();
    }
}

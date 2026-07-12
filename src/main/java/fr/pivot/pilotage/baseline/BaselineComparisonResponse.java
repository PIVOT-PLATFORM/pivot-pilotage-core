package fr.pivot.pilotage.baseline;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO of {@code GET .../baselines/{fromIndex}/compare/{toIndex}} (US22.4.9) — the
 * evolution between two frozen baselines, per task.
 *
 * <p>Takes a defensive, unmodifiable copy of {@link #tasks} (SpotBugs {@code EI_EXPOSE_REP}) —
 * mirrors {@code fr.pivot.pilotage.gantt.WbsTreeResponse}.
 *
 * @param fromIndex      the earlier/reference baseline's slot
 * @param fromCapturedAt when the {@code from} baseline was captured
 * @param toIndex        the later/compared-to baseline's slot
 * @param toCapturedAt   when the {@code to} baseline was captured
 * @param tasks          the per-task comparison rows (never {@code null})
 */
public record BaselineComparisonResponse(short fromIndex, Instant fromCapturedAt, short toIndex,
        Instant toCapturedAt, List<BaselineComparisonRowResponse> tasks) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the task list.
     */
    public BaselineComparisonResponse {
        tasks = List.copyOf(tasks);
    }
}

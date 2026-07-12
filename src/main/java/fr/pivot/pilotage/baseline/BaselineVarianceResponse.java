package fr.pivot.pilotage.baseline;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO of {@code GET .../baselines/{baselineIndex}/variance} (US22.4.9) — one baseline's
 * per-task écarts against the current temporal graph.
 *
 * <p>Takes a defensive, unmodifiable copy of {@link #tasks} (SpotBugs {@code EI_EXPOSE_REP}) —
 * mirrors {@code fr.pivot.pilotage.gantt.WbsTreeResponse}.
 *
 * @param baselineIndex      the compared baseline's slot ({@code 0..10})
 * @param baselineCapturedAt when the compared baseline was captured
 * @param tasks               the per-task variance rows (never {@code null})
 */
public record BaselineVarianceResponse(short baselineIndex, Instant baselineCapturedAt,
        List<TaskVarianceResponse> tasks) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the task list.
     */
    public BaselineVarianceResponse {
        tasks = List.copyOf(tasks);
    }
}

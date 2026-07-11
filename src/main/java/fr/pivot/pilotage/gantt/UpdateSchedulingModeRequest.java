package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.SchedulingMode;
import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code PATCH .../gantt/tasks/{taskId}/scheduling-mode} (US22.4.2) — switches a
 * task between {@code AUTO} (dates recomputed by the engine when a dependency/calendar changes) and
 * {@code MANUAL} (dates pinned; the engine only reports the variance versus the theoretical
 * automatic date, never overwriting them).
 *
 * <p>Switching to {@code MANUAL} persists a "manually planned" state (EN22.1): the task's current
 * dates become the pinned reference the engine compares against, so the drift can be surfaced
 * (plannedManual / wouldBeAuto / delta) without ever silently overwriting the user's dates.
 *
 * @param schedulingMode the target mode ({@code AUTO} or {@code MANUAL}); required
 */
public record UpdateSchedulingModeRequest(@NotNull SchedulingMode schedulingMode) {
}

package fr.pivot.pilotage.gantt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body of {@code PATCH .../gantt/tasks/{taskId}/effort} (US22.4.2) — sets the resource
 * units of a task's assignment, from which the engine derives the planned work
 * (<em>work = duration × units</em>).
 *
 * <p>The assignment is identified by its {@code resourceRef} (the logical resource, no cross-module
 * FK — ADR-006). When no assignment exists yet for that resource on the task, one is created;
 * otherwise its {@code units_percent} is updated. {@code unitsPercent} is required and must be a
 * well-formed number; a non-positive value is refused {@code 422}
 * ({@link InvalidTaskEffortException#nonPositiveUnits()}) so the derived work stays positive. The
 * derived {@code work_minutes} is never client-written — it is recomputed server-side from the
 * task's duration and these units.
 *
 * @param resourceRef  the logical resource reference the units apply to; required, non-blank
 * @param unitsPercent the assignment units in percent (e.g. {@code 100} = one full-time resource);
 *                     required, must be {@code > 0}
 */
public record UpdateTaskEffortRequest(
        @NotBlank String resourceRef,
        @NotNull BigDecimal unitsPercent) {
}

package fr.pivot.pilotage.gantt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST .../gantt/tasks} (US22.4.1a) — creates a task in a project's WBS,
 * optionally under an existing parent task.
 *
 * <p><strong>{@code wbsCode} is deliberately absent</strong>: the WBS code is a
 * <em>derived</em> server-side property (parent + rank), recomputed by the scheduling engine
 * (EN22.1) after every structural change, never written by the client. A client attempting to
 * supply one is rejected {@code 422} — see {@link DerivedFieldNotEditableException} and
 * {@link WbsTaskController}'s create endpoint, which rejects any request carrying a
 * {@code wbsCode} JSON property. The display {@code position} within the parent is optional; when
 * omitted the service appends the task at the end of its siblings.
 *
 * @param name            the task name; required, max 512 chars to match {@code pilotage.task.name}
 * @param parentTaskId    the WBS parent task id, or {@code null} to create a root-level task
 * @param position        the requested display order among siblings, or {@code null} to append
 * @param durationMinutes the leaf task's duration in worked minutes, or {@code null}
 */
public record CreateWbsTaskRequest(
        @NotBlank @Size(max = 512) String name,
        Long parentTaskId,
        @PositiveOrZero Integer position,
        @PositiveOrZero Integer durationMinutes) {
}

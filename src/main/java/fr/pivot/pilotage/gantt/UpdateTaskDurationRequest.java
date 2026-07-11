package fr.pivot.pilotage.gantt;

import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code PATCH .../gantt/tasks/{taskId}/duration} (US22.4.2) — sets a task's
 * duration in worked minutes.
 *
 * <p>{@code durationMinutes} is required and must be a well-formed integer. A negative value is
 * refused {@code 422} (service guard {@link InvalidTaskEffortException#negativeDuration(long)}); a
 * zero value is accepted only for a {@code MILESTONE} (a non-milestone zero-duration is refused
 * {@code 422}). A non-numeric JSON value never binds (Jackson → {@code 400}). Derived engine fields
 * ({@code earlyStart}, {@code lateFinish}, {@code totalSlackMinutes}, {@code isCritical}, …) are not
 * accepted here: supplying one is rejected {@code 422} (read-only, EN22.1) — see
 * {@link WbsExceptionHandler}.
 *
 * @param durationMinutes the new duration in worked minutes; required, {@code >= 0}
 */
public record UpdateTaskDurationRequest(@NotNull Integer durationMinutes) {
}

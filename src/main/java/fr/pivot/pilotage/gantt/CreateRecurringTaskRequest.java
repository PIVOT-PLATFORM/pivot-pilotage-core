package fr.pivot.pilotage.gantt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body of {@code POST .../gantt/tasks/recurring} (US22.4.6) &mdash; creates a periodic task
 * series and materialises its occurrences in one call.
 *
 * <p>{@code frequency} and {@code occurrenceCount} are <strong>deliberately not bean-validated</strong>
 * ({@code @NotNull}/{@code @Positive}) &mdash; the AC "Error: given a recurring task without a
 * frequency or a valid occurrence count&hellip; the creation is refused with an explicit message"
 * needs a caller-facing message in the response body, and Spring Boot's default bean-validation
 * error body omits field messages unless {@code server.error.include-message}/
 * {@code include-binding-errors} are set to {@code always} (not configured in this module). Both are
 * therefore validated explicitly by {@link RecurringTaskService}
 * ({@link InvalidRecurrenceException#missingFrequencyOrOccurrenceCount()}), mirroring exactly how
 * {@code fr.pivot.pilotage.roadmap.CreateMilestoneRequest#date} handles the same class of AC
 * (US22.3.4).
 *
 * @param name                the series name; also the prefix of every generated occurrence's name
 *                            (A11y &mdash; an accessible text label distinguishing occurrences, never
 *                            colour/shape only)
 * @param parentTaskId        the WBS parent task id, or {@code null} to create the series at the WBS
 *                            root
 * @param firstOccurrenceDate the anchor date of the first occurrence, before any working-calendar
 *                            shift (US22.4.5); required
 * @param frequency           the recurrence cadence; required (see class note &mdash; not
 *                            bean-validated)
 * @param intervalCount       the cadence multiplier ("every N days/weeks/months"), or {@code null} to
 *                            default to {@code 1}
 * @param occurrenceCount     the number of occurrences to generate; required and must be strictly
 *                            positive (see class note &mdash; not bean-validated), capped at
 *                            {@link RecurringTaskService#MAX_OCCURRENCES}
 * @param durationMinutes     each occurrence's duration in worked minutes, or {@code null}/{@code 0}
 *                            to generate zero-duration occurrences &mdash; rendered as milestones,
 *                            the same auto-classification as the US22.4.6 AC1 duration-0 rule
 */
public record CreateRecurringTaskRequest(
        @NotBlank @Size(max = 512) String name,
        Long parentTaskId,
        @NotNull LocalDate firstOccurrenceDate,
        RecurrenceFrequency frequency,
        @Positive Integer intervalCount,
        Integer occurrenceCount,
        @PositiveOrZero Integer durationMinutes) {
}

package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.ConstraintType;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request body of {@code PUT .../gantt/tasks/{taskId}/constraint} (US22.4.4 — «&nbsp;Contraintes de
 * date &amp; échéances&nbsp;») — sets (creates or replaces) the single {@code task_constraint} row of
 * a task, mirroring the referential MS Project carries (ASAP, ALAP, MSO, MFO, SNET, SNLT, FNET, FNLT
 * — {@link ConstraintType}, EN22.1a frozen contract §a) plus an independent soft {@code deadline}.
 *
 * <p><strong>{@code constraintDate} is required for every type except {@code ASAP}/{@code ALAP}</strong>
 * (US22.4.4 error AC) — a cross-field rule checked by {@link TaskConstraintService}, not expressible
 * as a single bean-validation annotation, mirroring how {@code DependencyService} validates the
 * self-dependency case in the service rather than on the record. Supplying a date on {@code ASAP}/
 * {@code ALAP} is not an error: the service clears it, matching the entity's documented invariant
 * (only date-bearing types carry a date).
 *
 * <p>{@code deadline} is independent of {@code constraintType} — a soft indicator (US22.4.4 AC: "ne
 * doit jamais bloquer le recalcul") that never constrains the CPM, only feeds the
 * {@code DEADLINE_MISSED} warning. Passing {@code null} clears it.
 *
 * @param constraintType the constraint type (required)
 * @param constraintDate the constraint date; required unless {@code constraintType} is {@code ASAP}
 *                        or {@code ALAP}, ignored (cleared) when it is
 * @param deadline       the soft deadline, or {@code null} to clear it
 */
public record UpsertTaskConstraintRequest(
        @NotNull ConstraintType constraintType,
        Instant constraintDate,
        Instant deadline) {
}

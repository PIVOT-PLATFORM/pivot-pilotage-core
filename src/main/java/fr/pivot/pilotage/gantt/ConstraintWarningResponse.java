package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.engine.SchedulingWarning;

/**
 * A single engine-emitted conflict/alert on a task's constraint or deadline (US22.4.4), scoped to
 * the task the caller is already browsing — {@link SchedulingWarning#taskId()} is therefore dropped
 * here (it is redundant with the enclosing {@link TaskConstraintResponse#taskId()}).
 *
 * <p><strong>A11y (US22.4.4 AC).</strong> {@code type} is a stable machine-readable discriminator
 * ({@code CONSTRAINT_CONFLICT}, {@code DEADLINE_MISSED}, {@code NEGATIVE_FLOAT}, {@code REJECTED})
 * a client can map to an icon, and {@code detail} is a human-readable sentence — the pair is what lets
 * {@code pivot-pilotage-ui} render an indicator that never relies on colour alone and can be announced
 * via {@code aria-live} without this API knowing anything about colour or ARIA. The alert is never
 * blocking (US22.4.4 AC: a deadline miss never fails the request; a constraint conflict never breaks
 * the honoured dependency) — {@code 200 OK} always carries these, they are not an HTTP error.
 *
 * @param type   the warning kind ({@link SchedulingWarning.WarningType})
 * @param detail a human-readable diagnostic
 */
public record ConstraintWarningResponse(SchedulingWarning.WarningType type, String detail) {
}

package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.ConstraintType;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO of the US22.4.4 constraint endpoints ({@code GET}/{@code PUT
 * .../gantt/tasks/{taskId}/constraint}) — never the JPA entity directly (CLAUDE.md §Standards).
 *
 * <p>A task that never had a {@code task_constraint} row persisted is reported as {@code ASAP} with
 * no date and no deadline — the engine's own default for an absent constraint (EN22.1b,
 * {@code TaskNode#constraintKind() == null} behaves exactly like {@code ASAP}), so a bare {@code GET}
 * never needs a {@code 404} to mean "no constraint set".
 *
 * <p>{@link #warnings} carries every current engine warning about <em>this</em> task
 * ({@code CONSTRAINT_CONFLICT}, {@code DEADLINE_MISSED}, {@code NEGATIVE_FLOAT}) — live-recomputed on
 * every {@code GET} (US22.4.4 Security AC: a conflict raised by an editor stays visible read-only to
 * every other role, without requiring a fresh write) and refreshed on every {@code PUT}.
 *
 * @param taskId         the task id
 * @param constraintType the constraint type; {@code ASAP} when no row is persisted
 * @param constraintDate the constraint date; {@code null} for {@code ASAP}/{@code ALAP} or when unset
 * @param deadline       the soft deadline, or {@code null}
 * @param warnings       the task's current scheduling warnings (possibly empty, never {@code null})
 */
public record TaskConstraintResponse(
        long taskId,
        ConstraintType constraintType,
        Instant constraintDate,
        Instant deadline,
        List<ConstraintWarningResponse> warnings) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the warnings list (SpotBugs
     * {@code EI_EXPOSE_REP}/{@code EI_EXPOSE_REP2}), mirroring {@code WbsTreeResponse}.
     */
    public TaskConstraintResponse {
        warnings = List.copyOf(warnings);
    }
}

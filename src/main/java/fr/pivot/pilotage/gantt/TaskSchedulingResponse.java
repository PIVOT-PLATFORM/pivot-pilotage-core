package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.SchedulingMode;

import java.time.Instant;

/**
 * Response DTO of the US22.4.2 duration/effort/mode endpoints — never the JPA entity directly
 * (CLAUDE.md §Standards). It reflects a task's post-recompute temporal state plus, for a
 * {@code MANUAL} task, the engine's manual-vs-auto variance ("the Gantt that lies" guard-rail,
 * EN22.1b).
 *
 * <p><strong>Manual variance (US22.4.2).</strong> When {@link #schedulingMode} is {@code MANUAL}
 * the pinned dates are never moved by the engine; instead {@link #plannedManual} (the pinned start),
 * {@link #wouldBeAuto} (the start the engine would have computed in AUTO) and {@link #deltaMinutes}
 * (the signed worked-minute drift, {@code plannedManual - wouldBeAuto}) quantify the divergence. For
 * an {@code AUTO} task those three fields are {@code null}/{@code 0}: there is nothing pinned, so no
 * variance exists.
 *
 * <p>{@link #effectiveMode} resolves a {@code null} task mode against the project's mode (a task with
 * no explicit mode inherits its project's), so the client always knows which regime actually applies.
 *
 * @param taskId          the task id
 * @param schedulingMode  the task's own mode (may be {@code null} — inherits the project's)
 * @param effectiveMode   the resolved mode actually applied (task mode, else the project's)
 * @param durationMinutes the task's duration in worked minutes, or {@code null}
 * @param workMinutes     the task's total planned work (Σ of its assignments' work = duration ×
 *                        units), or {@code null} when no resource is assigned
 * @param startDate       the current start (pinned for MANUAL, engine-computed for AUTO), or
 *                        {@code null}
 * @param finishDate      the current finish, or {@code null}
 * @param plannedManual   MANUAL only: the pinned start; {@code null} for AUTO
 * @param wouldBeAuto     MANUAL only: the start the engine would have computed in AUTO; {@code null}
 *                        for AUTO
 * @param deltaMinutes    MANUAL only: the signed worked-minute drift; {@code 0} for AUTO
 * @param revision        monotonic revision — optimistic co-editing lock and event ordering
 */
public record TaskSchedulingResponse(
        long taskId,
        SchedulingMode schedulingMode,
        SchedulingMode effectiveMode,
        Integer durationMinutes,
        Integer workMinutes,
        Instant startDate,
        Instant finishDate,
        Instant plannedManual,
        Instant wouldBeAuto,
        long deltaMinutes,
        int revision) {
}

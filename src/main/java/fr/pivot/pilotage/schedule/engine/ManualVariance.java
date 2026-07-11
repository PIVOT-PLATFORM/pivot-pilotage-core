package fr.pivot.pilotage.schedule.engine;

import java.time.Instant;

/**
 * Variance between a MANUAL task's pinned dates and the date the engine would have computed in AUTO
 * (EN22.1b) — the "Gantt that lies" guard-rail. The pinned dates are never moved; this record
 * quantifies the drift.
 *
 * @param taskId          the MANUAL task
 * @param plannedManual   the pinned (manual) start
 * @param wouldBeAuto     the start the engine would have computed in AUTO
 * @param deltaMinutes    the drift in worked minutes ({@code plannedManual - wouldBeAuto}); positive
 *                        means the manual date is later than the automatic one
 */
public record ManualVariance(
        long taskId,
        Instant plannedManual,
        Instant wouldBeAuto,
        long deltaMinutes) {
}

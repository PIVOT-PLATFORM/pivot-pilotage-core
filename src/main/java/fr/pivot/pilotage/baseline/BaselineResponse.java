package fr.pivot.pilotage.baseline;

import java.time.Instant;

/**
 * Response DTO for a baseline (US22.4.9) — never the JPA entity directly (CLAUDE.md §Standards).
 * Returned by {@code POST .../baselines} (pose/overwrite) and {@code GET .../baselines} (list).
 *
 * @param id            the baseline's stable id
 * @param baselineIndex the slot this baseline occupies ({@code 0..10})
 * @param capturedAt    when this baseline was captured
 * @param taskCount     the number of tasks frozen into this baseline's snapshots
 */
public record BaselineResponse(long id, short baselineIndex, Instant capturedAt, int taskCount) {
}

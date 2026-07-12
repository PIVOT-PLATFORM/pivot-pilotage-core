package fr.pivot.pilotage.baseline;

/**
 * Request body of {@code POST .../baselines} (US22.4.9) — poses (or, when the index is already used,
 * overwrites) a baseline.
 *
 * @param baselineIndex the target slot ({@code 0..10}), or {@code null} (and an absent/empty body)
 *                       to auto-assign the lowest free slot
 */
public record SetBaselineRequest(Short baselineIndex) {
}

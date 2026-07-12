package fr.pivot.pilotage.dashboard;

import java.util.Objects;

/**
 * Immutable tension snapshot contributed by a {@link PortfolioIndicatorSource} for one
 * {@code (tenantId, applicationId, kind)} triple (US23.2.2).
 *
 * @param level tension level; never {@code null}
 * @param label human-readable, caller-facing description of the tension (e.g. "Retard sur jalon
 *              stratégique") — required alongside {@link #level()} so the A11y AC ("les alertes ne
 *              sont pas restituées uniquement par la couleur") is satisfiable by the frontend: the
 *              backend always supplies text, never a bare severity code
 */
public record PortfolioIndicatorSnapshot(AlertLevel level, String label) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if {@code level} or {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public PortfolioIndicatorSnapshot {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}

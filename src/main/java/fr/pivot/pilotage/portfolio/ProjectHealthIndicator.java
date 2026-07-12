package fr.pivot.pilotage.portfolio;

import java.util.Objects;

/**
 * Immutable per-project health ("santé/météo") indicator surfaced in the consolidated portfolio
 * view (US23.2.1). Carries only a semantic {@link ProjectHealthStatus} — never a color — so the
 * frontend can satisfy the A11y AC (icon/text associated, not color alone) without this contract
 * ever changing.
 *
 * @param status the project's health status; {@link ProjectHealthStatus#NOT_SET} when no
 *               {@link ProjectHealthProvider} contributes an indicator for the project (error AC)
 */
public record ProjectHealthIndicator(ProjectHealthStatus status) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if {@code status} is {@code null}
     */
    public ProjectHealthIndicator {
        Objects.requireNonNull(status, "status");
    }

    /**
     * The explicit "non renseigné" indicator returned when no {@link ProjectHealthProvider}
     * contributes a health status for a project — never omitted, never defaulted to a misleading
     * value (error AC).
     *
     * @return a {@link ProjectHealthIndicator} carrying {@link ProjectHealthStatus#NOT_SET}
     */
    public static ProjectHealthIndicator notSet() {
        return new ProjectHealthIndicator(ProjectHealthStatus.NOT_SET);
    }
}

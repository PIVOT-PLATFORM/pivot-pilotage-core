package fr.pivot.pilotage.consolidation;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable aggregate contributed by <strong>another PIVOT module</strong> for a single application
 * (EN18.9, ADR-006/ADR-008). Budget (E26), risks (E21), decisions/ADR (E24)… are owned by other
 * modules and are <em>not</em> reachable from pilotage by an inter-module FK; each such module
 * publishes its per-application aggregate through {@link ApplicationDataContributor} over the PIVOT
 * bus, and it surfaces here.
 *
 * <p>The payload is intentionally an opaque, string-keyed metric map: pilotage does not model
 * another module's domain schema (that would re-introduce the very coupling ADR-006 forbids). Keys
 * are namespaced by the contributor (e.g. {@code "budget.consumedRatio"}), values are decoupled
 * primitives/strings the consolidation merely relays.
 *
 * @param moduleId the contributing module's id (e.g. {@code "budget"}, {@code "risk"}) — non-blank
 * @param metrics  the module's per-application metrics (defensively copied, immutable)
 */
public record ApplicationAggregateContribution(String moduleId, Map<String, Object> metrics) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code metrics} so the
     * contribution is fully immutable (SpotBugs {@code EI_EXPOSE_REP}).
     *
     * @throws NullPointerException     if {@code moduleId} or {@code metrics} is {@code null}
     * @throws IllegalArgumentException if {@code moduleId} is blank
     */
    public ApplicationAggregateContribution {
        Objects.requireNonNull(moduleId, "moduleId");
        if (moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
        metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
    }
}

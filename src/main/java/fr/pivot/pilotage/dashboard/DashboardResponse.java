package fr.pivot.pilotage.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Response body of {@code GET}/{@code PUT .../dashboard} (US23.2.2 AC1 — "une vue synthétique ou
 * détaillée adaptée au profil s'affiche avec ses widgets configurés") — never the
 * {@link DashboardConfig} JPA entity directly (CLAUDE.md §Standards).
 *
 * <p>When the caller has not configured a dashboard yet, {@link DashboardService#getDashboard}
 * returns a fresh, never-persisted default of this shape (mirrors
 * {@code fr.pivot.pilotage.profile.OrganizationProfileResolver}'s "jamais de ligne fantome ecrite"
 * precedent) — {@code profile} and {@code updatedAt} are {@code null}, {@code viewMode} defaults to
 * {@link DashboardViewMode#SYNTHETIC}, {@code widgets} is empty.
 *
 * @param userId    the owning user's id
 * @param profile   the persisted persona label, or {@code null} for the never-configured default
 * @param viewMode  the rendering mode
 * @param widgets   the configured widgets, in display order (possibly empty)
 * @param updatedAt when this dashboard was last saved, or {@code null} for the never-configured
 *                  default
 */
public record DashboardResponse(
        long userId,
        String profile,
        DashboardViewMode viewMode,
        List<DashboardWidgetResponse> widgets,
        Instant updatedAt) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code widgets} (SpotBugs
     * {@code EI_EXPOSE_REP}/{@code EI_EXPOSE_REP2}, mirrors
     * {@code fr.pivot.pilotage.consolidation.ApplicationConsolidation}).
     *
     * @throws NullPointerException if {@code widgets} is {@code null}
     */
    public DashboardResponse {
        widgets = List.copyOf(Objects.requireNonNull(widgets, "widgets"));
    }
}

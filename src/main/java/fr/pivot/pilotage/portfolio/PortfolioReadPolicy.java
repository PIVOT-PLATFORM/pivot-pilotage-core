package fr.pivot.pilotage.portfolio;

/**
 * Authorization policy gating every read of the consolidated portfolio view (US23.2.1). Security
 * AC: "given un utilisateur authentifié sans droit de lecture sur un projet de son propre tenant,
 * system retourne 403".
 *
 * <p><strong>Extension point, deliberately not wired to a real role/membership check yet</strong> —
 * mirrors {@code fr.pivot.pilotage.roadmap.RoadmapEditPolicy} exactly. {@code pivot-core-starter}
 * (the module exposing {@code TenantContext} and roles/project membership) is not published
 * (CLAUDE.md §gap, {@code TODO-SETUP.md} §5) — this module has no role or membership mechanism
 * available today. The only implementation wired today, {@link DenyAllPortfolioReadPolicy},
 * therefore <strong>always denies</strong> — fail-closed, never a hardcoded role and never a silent
 * bypass. Once {@code pivot-core-starter} publishes project membership, replace the wired bean with
 * a real implementation; {@link PortfolioController} and {@link PortfolioConsolidationService} do
 * not need to change.
 *
 * <p>Evaluated once per portfolio request (not per project): the AC's "sur un projet" wording
 * describes the caller's read right over their own tenant's project data in general — this module
 * has no finer-grained, per-project role concept yet (same gap). Independent of this policy, the
 * tenant-scoped repository reads in {@link PortfolioConsolidationService#consolidate(long)} already
 * guarantee no cross-tenant data ever reaches an authorized caller.
 */
public interface PortfolioReadPolicy {

    /**
     * Returns whether the current caller is authorized to read their tenant's consolidated
     * portfolio view.
     *
     * @return {@code true} if authorized; {@code false} maps to HTTP 403 at the controller
     */
    boolean isAuthorized();
}

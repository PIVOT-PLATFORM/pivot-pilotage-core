package fr.pivot.pilotage.roadmap;

/**
 * Authorization policy for every roadmap-rapide <strong>write</strong> (US22.3.1 — create a lane,
 * create an initiative, move/resize an initiative). Security AC: "seul un utilisateur ayant accès
 * au projet/portefeuille concerné peut créer ou modifier une initiative sur sa roadmap".
 *
 * <p><strong>Extension point, deliberately not wired to a real role/membership check yet</strong> —
 * mirrors {@code fr.pivot.pilotage.profile.OrganizationProfileOverridePolicy} (EN18.10 écart #3)
 * exactly. {@code pivot-core-starter} (the module exposing {@code TenantContext} and roles/project
 * membership) is not published (CLAUDE.md §gap, {@code TODO-SETUP.md} §5) — this module has no
 * role or membership mechanism available today. The only implementation wired today,
 * {@link DenyAllRoadmapEditPolicy}, therefore <strong>always denies</strong> — fail-closed, never a
 * hardcoded role and never a silent bypass. Once {@code pivot-core-starter} publishes project
 * membership, replace the wired bean with a real implementation; {@link RoadmapController} and
 * {@link RoadmapService} do not need to change.
 *
 * <p>Read endpoints (list lanes, list initiatives) are <strong>not</strong> gated by this policy —
 * the AC only restricts create/modify. Reads remain governed by the tenant/team/project isolation
 * check ({@link ProjectNotFoundException}, 404 non-disclosure) applied by every roadmap-rapide
 * endpoint regardless of this policy.
 */
public interface RoadmapEditPolicy {

    /**
     * Returns whether the current caller is authorized to create or modify roadmap-rapide
     * content (lanes, initiatives).
     *
     * @return {@code true} if authorized; {@code false} maps to HTTP 403 at the controller
     */
    boolean isAuthorized();
}

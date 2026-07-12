package fr.pivot.pilotage.baseline;

/**
 * Authorization policy for every baseline <strong>write</strong> (US22.4.9 — pose, overwrite,
 * delete). Security AC: "seul un utilisateur avec un rôle PMO ou chef de projet peut poser, écraser
 * ou supprimer une baseline ; un contributeur planning ne peut que consulter les écarts".
 *
 * <p><strong>Extension point, deliberately not wired to a real role/membership check yet</strong> —
 * mirrors {@code fr.pivot.pilotage.gantt.WbsEditPolicy} exactly. {@code pivot-core-starter} (the
 * module exposing {@code TenantContext} and roles/project membership) is not published (CLAUDE.md
 * §gap, {@code TODO-SETUP.md} §5). The only implementation wired today,
 * {@link DenyAllBaselineEditPolicy}, therefore <strong>always denies</strong> — fail-closed, never a
 * hardcoded role and never a silent bypass. Once the starter publishes project membership, replace
 * the wired bean with a real implementation (checking for PMO / chef de projet specifically, unlike
 * the broader WBS edit role); {@link BaselineController} and {@link BaselineService} do not need to
 * change.
 *
 * <p>Read endpoints (list, variance, compare) are <strong>not</strong> gated by this policy — a
 * "contributeur planning" may consult écarts freely; they remain governed only by the tenant/team/
 * project isolation check ({@link BaselineProjectNotFoundException}, 404 non-disclosure).
 */
public interface BaselineEditPolicy {

    /**
     * Returns whether the current caller is authorized to pose, overwrite or delete a project's
     * baselines.
     *
     * @return {@code true} if authorized; {@code false} maps to HTTP 403 at the controller
     */
    boolean isAuthorized();
}

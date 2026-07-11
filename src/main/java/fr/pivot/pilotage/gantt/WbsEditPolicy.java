package fr.pivot.pilotage.gantt;

/**
 * Authorization policy for every WBS Gantt <strong>write</strong> (US22.4.1a/b/c — create a task,
 * indent/outdent, reorder, edit). Security AC (US22.4.1b): "seul un utilisateur ayant un rôle
 * d'édition sur le projet (chef de projet / contributeur planning) peut modifier la hiérarchie ; un
 * rôle lecture seule reçoit {@code 403 Forbidden}".
 *
 * <p><strong>Extension point, deliberately not wired to a real role/membership check yet</strong> —
 * mirrors {@code fr.pivot.pilotage.roadmap.RoadmapEditPolicy} exactly. {@code pivot-core-starter}
 * (the module exposing {@code TenantContext} and roles/project membership) is not published
 * (CLAUDE.md §gap, {@code TODO-SETUP.md} §5). The only implementation wired today,
 * {@link DenyAllWbsEditPolicy}, therefore <strong>always denies</strong> — fail-closed, never a
 * hardcoded role and never a silent bypass. Once the starter publishes project membership, replace
 * the wired bean with a real implementation; {@link WbsTaskController} and {@link WbsTaskService} do
 * not need to change.
 *
 * <p>Read endpoints (the WBS tree) are <strong>not</strong> gated by this policy — they remain
 * governed by the tenant/team/project isolation check ({@link WbsProjectNotFoundException}, 404
 * non-disclosure).
 */
public interface WbsEditPolicy {

    /**
     * Returns whether the current caller is authorized to modify a project's WBS.
     *
     * @return {@code true} if authorized; {@code false} maps to HTTP 403 at the controller
     */
    boolean isAuthorized();
}

package fr.pivot.pilotage.profile;

/**
 * Authorization policy for {@code PUT /api/pilotage/organization-profile/{tenantId}} (EN18.10
 * écart #3 — security AC: "si un override des valeurs par défaut est exposé, il est réservé à un
 * rôle habilité (DSI / admin plateforme), sinon 403").
 *
 * <p><strong>Extension point, deliberately not wired to a real role yet.</strong>
 * {@code pivot-core-starter} (the module exposing {@code TenantContext} and roles) is not
 * published (CLAUDE.md §gap, {@code TODO-SETUP.md} §5) — this module has <strong>no</strong> role
 * or claim mechanism available today (verified: no {@code SecurityConfig}, no JWT/claims consumer
 * exists anywhere in this repo). The only implementation wired today,
 * {@link DenyAllOrganizationProfileOverridePolicy}, therefore <strong>always denies</strong> —
 * fail-closed, never a hardcoded role and never a silent bypass. Once {@code pivot-core-starter}
 * publishes roles, replace the wired bean with a real implementation reading the caller's role
 * (e.g. DSI / admin plateforme) — {@link OrganizationProfileController} and
 * {@link OrganizationProfileOverrideService} do not need to change.
 */
public interface OrganizationProfileOverridePolicy {

    /**
     * Returns whether the current caller is authorized to write an organization-profile override.
     *
     * @return {@code true} if authorized; {@code false} maps to HTTP 403 at the controller
     */
    boolean isAuthorized();
}

package fr.pivot.pilotage.profile;

/**
 * Thrown when {@link OrganizationProfileOverridePolicy#isAuthorized()} refuses an organization-
 * profile override write (EN18.10 écart #3, security AC). Maps to HTTP 403 at
 * {@link OrganizationProfileController}.
 */
public class OrganizationProfileOverrideForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Builds the exception with a message documenting the current (deny-all) posture. */
    public OrganizationProfileOverrideForbiddenException() {
        super("Organization profile override requires an authorized role (DSI / admin plateforme); "
                + "no role mechanism is wired yet (pivot-core-starter gap, TODO-SETUP.md §5) — "
                + "denying by default");
    }
}

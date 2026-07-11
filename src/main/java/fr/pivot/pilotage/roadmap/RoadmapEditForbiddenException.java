package fr.pivot.pilotage.roadmap;

/**
 * Thrown by {@link RoadmapController} when {@link RoadmapEditPolicy#isAuthorized()} denies a
 * roadmap-rapide write (US22.3.1). Mapped to HTTP 403 by {@link RoadmapExceptionHandler}. Mirrors
 * {@code fr.pivot.pilotage.profile.OrganizationProfileOverrideForbiddenException} (EN18.10 écart
 * #3).
 */
public class RoadmapEditForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Builds the exception with a message documenting the current (deny-all) posture. */
    public RoadmapEditForbiddenException() {
        super("Roadmap-rapide write requires project/portfolio access; no membership mechanism is "
                + "wired yet (pivot-core-starter gap, TODO-SETUP.md §5) — denying by default");
    }
}

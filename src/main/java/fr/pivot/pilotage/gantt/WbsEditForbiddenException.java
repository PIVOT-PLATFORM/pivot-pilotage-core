package fr.pivot.pilotage.gantt;

/**
 * Thrown by {@link WbsTaskController} when {@link WbsEditPolicy#isAuthorized()} denies a WBS write
 * (create / indent / outdent / reorder / edit — US22.4.1a/b/c). Mapped to HTTP {@code 403} by
 * {@link WbsExceptionHandler}. Mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapEditForbiddenException}.
 */
public class WbsEditForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Builds the exception with a message documenting the current (deny-all) posture. */
    public WbsEditForbiddenException() {
        super("WBS edition requires a project edit role (chef de projet / contributeur planning); "
                + "no membership mechanism is wired yet (pivot-core-starter gap, TODO-SETUP.md §5) "
                + "— denying by default");
    }
}

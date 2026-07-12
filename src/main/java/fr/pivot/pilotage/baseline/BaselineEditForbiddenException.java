package fr.pivot.pilotage.baseline;

/**
 * Thrown by {@link BaselineController} when {@link BaselineEditPolicy#isAuthorized()} denies a
 * baseline write (pose / overwrite / delete — US22.4.9). Mapped to HTTP {@code 403} by
 * {@link BaselineExceptionHandler}. Mirrors
 * {@code fr.pivot.pilotage.gantt.WbsEditForbiddenException}.
 */
public class BaselineEditForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Builds the exception with a message documenting the current (deny-all) posture. */
    public BaselineEditForbiddenException() {
        super("Baseline pose/overwrite/delete requires a PMO or chef de projet role; "
                + "no membership mechanism is wired yet (pivot-core-starter gap, TODO-SETUP.md §5) "
                + "— denying by default");
    }
}

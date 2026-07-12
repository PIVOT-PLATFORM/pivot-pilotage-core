package fr.pivot.pilotage.portfolio;

/**
 * Thrown by {@link PortfolioController} when {@link PortfolioReadPolicy#isAuthorized()} denies a
 * portfolio read (US23.2.1). Mapped to HTTP 403 by {@link PortfolioExceptionHandler}. Mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapEditForbiddenException}.
 */
public class PortfolioReadForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Builds the exception with a message documenting the current (deny-all) posture. */
    public PortfolioReadForbiddenException() {
        super("Portfolio read requires project/portfolio access; no membership mechanism is "
                + "wired yet (pivot-core-starter gap, TODO-SETUP.md §5) — denying by default");
    }
}

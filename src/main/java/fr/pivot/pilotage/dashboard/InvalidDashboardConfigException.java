package fr.pivot.pilotage.dashboard;

/**
 * Thrown by {@link DashboardService#saveDashboard} when the top-level dashboard configuration
 * (outside of any individual widget — see {@link InvalidDashboardWidgetException} for
 * widget-scoped failures) is invalid at save time (US23.2.2 Error AC: "system retourne 400 avec
 * message explicite et ne persiste pas"). Mapped to 400 with an {@link ApiError} body by
 * {@link DashboardExceptionHandler} — mirrors
 * {@code fr.pivot.pilotage.roadmap.InvalidHorizonException}'s rationale exactly: a required enum
 * field is deliberately not JSON-validated via {@code @NotNull} alone so the 400 response carries a
 * caller-facing, machine-readable code.
 */
public class InvalidDashboardConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** No target {@link DashboardViewMode} was supplied. */
    static final String CODE_VIEW_MODE_REQUIRED = "VIEW_MODE_REQUIRED";

    private final String code;

    /**
     * Builds the exception.
     *
     * @param code    the machine-readable error code carried by the {@link ApiError} body
     * @param message the human-readable explanation
     */
    InvalidDashboardConfigException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the machine-readable error code.
     *
     * @return the code (e.g. {@link #CODE_VIEW_MODE_REQUIRED})
     */
    public String code() {
        return code;
    }
}

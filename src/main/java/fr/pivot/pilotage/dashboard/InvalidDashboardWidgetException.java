package fr.pivot.pilotage.dashboard;

/**
 * Thrown by {@link DashboardService#saveDashboard} when a single widget in the submitted layout is
 * invalid (US23.2.2 Error AC: "given une configuration de widget invalide (widget inconnu,
 * disposition hors bornes) à l'enregistrement, system retourne 400 avec message explicite et ne
 * persiste pas"). Mapped to 400 with an {@link ApiError} body by {@link DashboardExceptionHandler}.
 *
 * <p>The whole request is validated <strong>before</strong> anything is persisted (see
 * {@link DashboardService#saveDashboard}) — one invalid widget rejects the entire save, never a
 * partial layout.
 *
 * <p>PO Agent decision (Gate 1, AC wording gives "widget inconnu"/"disposition hors bornes" as
 * illustrative examples of "configuration de widget invalide", not an exhaustive list): a widget
 * referencing an {@code applicationId} that does not exist, or belongs to a different
 * tenant/team, is treated as the same "invalid widget configuration" 400 case
 * ({@link #CODE_WIDGET_APPLICATION_NOT_FOUND}) rather than a resource-fetch 404 — it is a property
 * of the submitted widget definition, not of the dashboard resource being addressed by path.
 */
public class InvalidDashboardWidgetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** No {@link DashboardWidgetType} was supplied for a widget. */
    static final String CODE_WIDGET_TYPE_REQUIRED = "WIDGET_TYPE_REQUIRED";

    /** No target application id was supplied for a widget. */
    static final String CODE_WIDGET_APPLICATION_REQUIRED = "WIDGET_APPLICATION_REQUIRED";

    /** The widget's target application does not exist, or is outside the caller's tenant/team. */
    static final String CODE_WIDGET_APPLICATION_NOT_FOUND = "WIDGET_APPLICATION_NOT_FOUND";

    /**
     * The widget's grid disposition ({@code gridRow}/{@code gridColumn}/{@code gridWidth}/
     * {@code gridHeight}/{@code position}) is outside the dashboard grid's bounds.
     */
    static final String CODE_WIDGET_DISPOSITION_OUT_OF_BOUNDS = "WIDGET_DISPOSITION_OUT_OF_BOUNDS";

    private final String code;

    /**
     * Builds the exception.
     *
     * @param code    the machine-readable error code carried by the {@link ApiError} body
     * @param message the human-readable explanation
     */
    InvalidDashboardWidgetException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the machine-readable error code.
     *
     * @return the code (e.g. {@link #CODE_WIDGET_TYPE_REQUIRED})
     */
    public String code() {
        return code;
    }
}

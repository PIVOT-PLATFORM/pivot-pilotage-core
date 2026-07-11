package fr.pivot.pilotage.gantt;

/**
 * Minimal error body returned by {@link WbsExceptionHandler} for the WBS validation errors that
 * must carry an explicit, caller-facing message (unlike the bodyless 404s used for non-disclosed
 * tenant/team/project isolation failures — see {@link WbsProjectNotFoundException},
 * {@link WbsTaskNotFoundException}).
 *
 * <p>Package-local mirror of {@code fr.pivot.pilotage.roadmap.ApiError}, kept in this package so the
 * WBS error contract is self-contained and independent of the roadmap package's evolution.
 *
 * @param code    a stable machine-readable error code (e.g. {@code ILLEGAL_WBS_MOVE})
 * @param message a human-readable message describing the validation failure
 */
public record WbsApiError(String code, String message) {
}

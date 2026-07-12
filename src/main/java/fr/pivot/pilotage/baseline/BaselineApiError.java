package fr.pivot.pilotage.baseline;

/**
 * Minimal error body returned by {@link BaselineExceptionHandler} for the baseline validation errors
 * that must carry an explicit, caller-facing message (unlike the bodyless 404s used for
 * non-disclosed tenant/team/project/baseline isolation failures — see
 * {@link BaselineProjectNotFoundException}, {@link BaselineNotFoundException}).
 *
 * <p>Package-local mirror of {@code fr.pivot.pilotage.gantt.WbsApiError}, kept in this package so
 * the baseline error contract is self-contained and independent of the gantt package's evolution.
 *
 * @param code    a stable machine-readable error code (e.g. {@code BASELINE_LIMIT_EXCEEDED})
 * @param message a human-readable message describing the validation failure
 */
public record BaselineApiError(String code, String message) {
}

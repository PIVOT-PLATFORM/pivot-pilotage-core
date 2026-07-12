package fr.pivot.pilotage.dashboard;

/**
 * Minimal error body returned by {@link DashboardExceptionHandler} for the dashboard save
 * validation errors that must carry an explicit, caller-facing message (unlike a bodyless 403/404
 * elsewhere) — mirrors {@code fr.pivot.pilotage.roadmap.ApiError} (each package owns its own copy
 * so a controller's error mapping never surprises another package's, cf.
 * {@link DashboardExceptionHandler} javadoc).
 *
 * @param code    a stable machine-readable error code (e.g. {@code VIEW_MODE_REQUIRED})
 * @param message a human-readable message describing the validation failure
 */
public record ApiError(String code, String message) {
}

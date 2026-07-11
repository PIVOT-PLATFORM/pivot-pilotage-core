package fr.pivot.pilotage.roadmap;

/**
 * Minimal error body returned by {@link RoadmapExceptionHandler} for the roadmap-rapide validation
 * errors that must carry an explicit, caller-facing message (unlike the bodyless 404s used
 * elsewhere in this module for non-disclosed tenant/team/project isolation failures — see
 * {@link ProjectNotFoundException}, {@link InitiativeNotFoundException}).
 *
 * <p>Introduced by US22.3.1 to satisfy the AC "the action is rejected and a message indicates a
 * lane is required" — a bodyless response would not carry that message.
 *
 * @param code    a stable machine-readable error code (e.g. {@code LANE_NOT_FOUND})
 * @param message a human-readable message describing the validation failure
 */
public record ApiError(String code, String message) {
}

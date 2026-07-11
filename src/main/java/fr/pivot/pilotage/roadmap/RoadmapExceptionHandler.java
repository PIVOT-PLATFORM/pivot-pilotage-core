package fr.pivot.pilotage.roadmap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the {@code fr.pivot.pilotage.roadmap} package's domain exceptions to HTTP responses for
 * {@link RoadmapController} (US22.3.1).
 *
 * <p>Scoped to this package only ({@code basePackages}) — mirrors
 * {@code fr.pivot.pilotage.profile.OrganizationProfileExceptionHandler}'s package-scoped advice so
 * this new controller's error mapping never surprises another package's controller.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.roadmap")
class RoadmapExceptionHandler {

    /**
     * Maps an unresolved project (unknown tenant/team/project, or cross-tenant/team) to a bodyless
     * 404 — non-disclosure posture (CLAUDE.md §Isolation tenant).
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<Void> handleProjectNotFound(final ProjectNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps an unresolved initiative to a bodyless 404 — same non-disclosure posture.
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(InitiativeNotFoundException.class)
    ResponseEntity<Void> handleInitiativeNotFound(final InitiativeNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a missing/invalid lane reference to 400 with a caller-facing message (AC: "un message
     * indique qu'une lane est requise") — the error code distinguishes "no lane supplied"
     * ({@link LaneNotFoundException#CODE_REQUIRED}) from "unknown/foreign lane"
     * ({@link LaneNotFoundException#CODE_NOT_FOUND}).
     *
     * @param ex the thrown exception
     * @return a 400 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(LaneNotFoundException.class)
    ResponseEntity<ApiError> handleLaneNotFound(final LaneNotFoundException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.code(), ex.getMessage()));
    }

    /**
     * Maps an inconsistent approximate period to 400 with a caller-facing message.
     *
     * @param ex the thrown exception
     * @return a 400 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(InvalidInitiativePeriodException.class)
    ResponseEntity<ApiError> handleInvalidPeriod(final InvalidInitiativePeriodException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_PERIOD", ex.getMessage()));
    }

    /**
     * Maps a duplicate lane label to 409 with a caller-facing message.
     *
     * @param ex the thrown exception
     * @return a 409 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(DuplicateLaneNameException.class)
    ResponseEntity<ApiError> handleDuplicateLane(final DuplicateLaneNameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("LANE_DUPLICATE", ex.getMessage()));
    }

    /**
     * Maps a denied write (role gate) to a bodyless 403.
     *
     * @param ex the thrown exception
     * @return a bodyless 403 response
     */
    @ExceptionHandler(RoadmapEditForbiddenException.class)
    ResponseEntity<Void> handleForbidden(final RoadmapEditForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Maps an unresolved milestone to a bodyless 404 — same non-disclosure posture (US22.3.4).
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(MilestoneNotFoundException.class)
    ResponseEntity<Void> handleMilestoneNotFound(final MilestoneNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a missing/out-of-bounds milestone date to 400 with a caller-facing message (US22.3.4).
     *
     * @param ex the thrown exception
     * @return a 400 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(InvalidMilestoneDateException.class)
    ResponseEntity<ApiError> handleInvalidMilestoneDate(final InvalidMilestoneDateException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.code(), ex.getMessage()));
    }
}

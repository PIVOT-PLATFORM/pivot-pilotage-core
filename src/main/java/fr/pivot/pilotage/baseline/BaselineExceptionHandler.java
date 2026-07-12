package fr.pivot.pilotage.baseline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the {@code fr.pivot.pilotage.baseline} package's domain exceptions to HTTP responses for
 * {@link BaselineController} (US22.4.9).
 *
 * <p>Scoped to this package only ({@code basePackages}) — mirrors
 * {@code fr.pivot.pilotage.gantt.WbsExceptionHandler}'s package-scoped advice so this controller's
 * error mapping never surprises another package's controller.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.baseline")
class BaselineExceptionHandler {

    /**
     * Maps an unresolved project (unknown tenant/team/project, or cross-tenant/team) to a bodyless
     * {@code 404} — non-disclosure posture (CLAUDE.md §Isolation tenant).
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(BaselineProjectNotFoundException.class)
    ResponseEntity<Void> handleProjectNotFound(final BaselineProjectNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps an unresolved baseline index to a bodyless {@code 404} — same non-disclosure posture.
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(BaselineNotFoundException.class)
    ResponseEntity<Void> handleBaselineNotFound(final BaselineNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a denied write (role gate) to a bodyless {@code 403}.
     *
     * @param ex the thrown exception
     * @return a bodyless 403 response
     */
    @ExceptionHandler(BaselineEditForbiddenException.class)
    ResponseEntity<Void> handleForbidden(final BaselineEditForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Maps an out-of-range {@code baselineIndex} to {@code 422 Unprocessable Entity} with an explicit
     * message.
     *
     * @param ex the thrown exception
     * @return a 422 response carrying a {@link BaselineApiError} body
     */
    @ExceptionHandler(InvalidBaselineIndexException.class)
    ResponseEntity<BaselineApiError> handleInvalidIndex(final InvalidBaselineIndexException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new BaselineApiError(InvalidBaselineIndexException.CODE, ex.getMessage()));
    }

    /**
     * Maps an attempt to auto-assign a 12th baseline (US22.4.9 error AC) to {@code 409 Conflict} with
     * a message inviting the caller to overwrite or delete an existing baseline.
     *
     * @param ex the thrown exception
     * @return a 409 response carrying a {@link BaselineApiError} body
     */
    @ExceptionHandler(BaselineLimitExceededException.class)
    ResponseEntity<BaselineApiError> handleLimitExceeded(final BaselineLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new BaselineApiError(BaselineLimitExceededException.CODE, ex.getMessage()));
    }
}

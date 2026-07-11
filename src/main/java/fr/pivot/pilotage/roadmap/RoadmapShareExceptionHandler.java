package fr.pivot.pilotage.roadmap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the US22.3.5 share-link domain exceptions to HTTP responses, for both
 * {@link RoadmapShareLinkController} (authenticated management) and
 * {@link RoadmapShareViewController} (public consultation).
 *
 * <p><strong>A separate advice bean from {@link RoadmapExceptionHandler}</strong> — deliberately,
 * to stay additive: this US runs in parallel with two sibling US on the same repo (now/next/later,
 * milestones), each of which may also touch the roadmap exception-handling surface. Spring
 * supports any number of {@code @RestControllerAdvice} beans scoped to the same
 * {@code basePackages}; as long as no two declare an {@code @ExceptionHandler} for the same
 * exception type (true here — every type below is new), there is no ambiguity and no need to
 * merge into the existing file.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.roadmap")
class RoadmapShareExceptionHandler {

    /**
     * Maps an unresolved share link id (authenticated management context) to a bodyless 404 —
     * non-disclosure posture, same as {@link RoadmapExceptionHandler#handleProjectNotFound}.
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(ShareLinkNotFoundException.class)
    ResponseEntity<Void> handleShareLinkNotFound(final ShareLinkNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a denied public consultation (unknown/revoked/expired token) to 404 with an explicit,
     * caller-facing message (AC: "l'accès est refusé avec un message explicite").
     *
     * @param ex the thrown exception
     * @return a 404 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(ShareLinkAccessDeniedException.class)
    ResponseEntity<ApiError> handleShareLinkAccessDenied(final ShareLinkAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SHARE_LINK_INVALID", ex.getMessage()));
    }

    /**
     * Maps an already-past-or-present {@code expiresAt} on creation to 400 with a caller-facing
     * message.
     *
     * @param ex the thrown exception
     * @return a 400 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(InvalidShareLinkExpiryException.class)
    ResponseEntity<ApiError> handleInvalidExpiry(final InvalidShareLinkExpiryException ex) {
        return ResponseEntity.badRequest().body(new ApiError("SHARE_LINK_EXPIRY_INVALID", ex.getMessage()));
    }
}

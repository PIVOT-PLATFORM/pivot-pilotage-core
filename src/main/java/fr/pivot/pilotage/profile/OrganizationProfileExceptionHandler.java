package fr.pivot.pilotage.profile;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the {@code fr.pivot.pilotage.profile} package's domain exceptions to HTTP responses for
 * {@link OrganizationProfileController} (EN18.10 écart #3).
 *
 * <p>Scoped to this package only ({@code basePackages}) — this is the first controller in the
 * module; a package-scoped advice avoids surprising future controllers in other packages that may
 * want their own mapping.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.profile")
class OrganizationProfileExceptionHandler {

    /**
     * Maps an unknown tenant to 404 — never confirms whether the tenant exists (non-disclosure
     * posture, CLAUDE.md §Isolation tenant).
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(TenantNotFoundException.class)
    ResponseEntity<Void> handleTenantNotFound(final TenantNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps an unknown or cross-tenant team to 404 — same non-disclosure posture.
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(TeamNotFoundException.class)
    ResponseEntity<Void> handleTeamNotFound(final TeamNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a denied override (role gate) to 403.
     *
     * @param ex the thrown exception
     * @return a bodyless 403 response
     */
    @ExceptionHandler(OrganizationProfileOverrideForbiddenException.class)
    ResponseEntity<Void> handleForbidden(final OrganizationProfileOverrideForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}

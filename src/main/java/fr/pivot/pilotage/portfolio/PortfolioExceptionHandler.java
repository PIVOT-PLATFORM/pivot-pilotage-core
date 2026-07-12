package fr.pivot.pilotage.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the {@code fr.pivot.pilotage.portfolio} package's domain exceptions to HTTP responses for
 * {@link PortfolioController} (US23.2.1).
 *
 * <p>Scoped to this package only ({@code basePackages}) — mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapExceptionHandler}'s package-scoped advice so this new
 * controller's error mapping never surprises another package's controller.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.portfolio")
class PortfolioExceptionHandler {

    /**
     * Maps a denied read (role gate) to a bodyless 403.
     *
     * @param ex the thrown exception
     * @return a bodyless 403 response
     */
    @ExceptionHandler(PortfolioReadForbiddenException.class)
    ResponseEntity<Void> handleForbidden(final PortfolioReadForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}

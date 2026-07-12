package fr.pivot.pilotage.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the {@code fr.pivot.pilotage.dashboard} package's domain exceptions to HTTP responses for
 * {@link DashboardController} (US23.2.2).
 *
 * <p>Scoped to this package only ({@code basePackages}) — mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapExceptionHandler}'s package-scoped advice so this
 * controller's error mapping never surprises another package's controller.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.dashboard")
class DashboardExceptionHandler {

    /**
     * Maps an invalid top-level dashboard configuration to 400 with a caller-facing message.
     *
     * @param ex the thrown exception
     * @return a 400 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(InvalidDashboardConfigException.class)
    ResponseEntity<ApiError> handleInvalidConfig(final InvalidDashboardConfigException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.code(), ex.getMessage()));
    }

    /**
     * Maps an invalid widget configuration to 400 with a caller-facing message.
     *
     * @param ex the thrown exception
     * @return a 400 response carrying an {@link ApiError} body
     */
    @ExceptionHandler(InvalidDashboardWidgetException.class)
    ResponseEntity<ApiError> handleInvalidWidget(final InvalidDashboardWidgetException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.code(), ex.getMessage()));
    }
}

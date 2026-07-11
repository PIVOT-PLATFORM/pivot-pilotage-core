package fr.pivot.pilotage.gantt;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.PropertyBindingException;

/**
 * Maps the {@code fr.pivot.pilotage.gantt} package's domain exceptions to HTTP responses for
 * {@link WbsTaskController} (US22.4.1a/b/c).
 *
 * <p>Scoped to this package only ({@code basePackages}) — mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapExceptionHandler}'s package-scoped advice so this
 * controller's error mapping never surprises another package's controller.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.gantt")
class WbsExceptionHandler {

    /**
     * Maps an unresolved project (unknown tenant/team/project, or cross-tenant/team) to a bodyless
     * {@code 404} — non-disclosure posture (CLAUDE.md §Isolation tenant).
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(WbsProjectNotFoundException.class)
    ResponseEntity<Void> handleProjectNotFound(final WbsProjectNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps an unresolved task to a bodyless {@code 404} — same non-disclosure posture.
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(WbsTaskNotFoundException.class)
    ResponseEntity<Void> handleTaskNotFound(final WbsTaskNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a WBS-hierarchy cycle to {@code 409 Conflict} (design decision D4 — distinct from the
     * engine's dependency-graph {@code SCHEDULE_CYCLE}).
     *
     * @param ex the thrown exception
     * @return a 409 response carrying a {@link WbsApiError} body
     */
    @ExceptionHandler(WbsHierarchyCycleException.class)
    ResponseEntity<WbsApiError> handleHierarchyCycle(final WbsHierarchyCycleException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new WbsApiError("WBS_HIERARCHY_CYCLE", ex.getMessage()));
    }

    /**
     * Maps an illegal indent/outdent (indent of the first task, outdent at root) to
     * {@code 422 Unprocessable Entity} with an explicit message.
     *
     * @param ex the thrown exception
     * @return a 422 response carrying a {@link WbsApiError} body
     */
    @ExceptionHandler(IllegalWbsMoveException.class)
    ResponseEntity<WbsApiError> handleIllegalMove(final IllegalWbsMoveException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new WbsApiError(IllegalWbsMoveException.CODE, ex.getMessage()));
    }

    /**
     * Maps a rejected derived-field write (client {@code wbsCode}, or a summary's aggregated field)
     * to {@code 422 Unprocessable Entity} with an explicit message.
     *
     * @param ex the thrown exception
     * @return a 422 response carrying a {@link WbsApiError} body
     */
    @ExceptionHandler(DerivedFieldNotEditableException.class)
    ResponseEntity<WbsApiError> handleDerivedField(final DerivedFieldNotEditableException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new WbsApiError(DerivedFieldNotEditableException.CODE, ex.getMessage()));
    }

    /**
     * Maps a denied write (role gate) to a bodyless {@code 403}.
     *
     * @param ex the thrown exception
     * @return a bodyless 403 response
     */
    @ExceptionHandler(WbsEditForbiddenException.class)
    ResponseEntity<Void> handleForbidden(final WbsEditForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Maps an unresolved dependency (unknown/cross-tenant, or endpoint task outside the project,
     * US22.4.3) to a bodyless {@code 404} — same non-disclosure posture.
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(DependencyNotFoundException.class)
    ResponseEntity<Void> handleDependencyNotFound(final DependencyNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a self-dependency (a task linked to itself, US22.4.3 error AC) to {@code 422 Unprocessable
     * Entity} with an explicit message.
     *
     * @param ex the thrown exception
     * @return a 422 response carrying a {@link WbsApiError} body
     */
    @ExceptionHandler(InvalidDependencyException.class)
    ResponseEntity<WbsApiError> handleInvalidDependency(final InvalidDependencyException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new WbsApiError(InvalidDependencyException.CODE, ex.getMessage()));
    }

    /**
     * Maps a duplicate dependency (same predecessor/successor/link type already exists, US22.4.3
     * error AC) to {@code 409 Conflict} with an explicit message.
     *
     * @param ex the thrown exception
     * @return a 409 response carrying a {@link WbsApiError} body
     */
    @ExceptionHandler(DuplicateDependencyException.class)
    ResponseEntity<WbsApiError> handleDuplicateDependency(final DuplicateDependencyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new WbsApiError(DuplicateDependencyException.CODE, ex.getMessage()));
    }

    /**
     * Maps a temporal-graph cycle (engine {@code SCHEDULE_CYCLE}, US22.4.3) to {@code 409 Conflict}
     * carrying the engine's own code — deliberately distinct from the WBS hierarchy cycle
     * ({@code WBS_HIERARCHY_CYCLE}, decision D4).
     *
     * @param ex the thrown exception
     * @return a 409 response carrying a {@link WbsApiError} body
     */
    @ExceptionHandler(DependencyCycleException.class)
    ResponseEntity<WbsApiError> handleDependencyCycle(final DependencyCycleException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new WbsApiError(DependencyCycleException.CODE, ex.getMessage()));
    }

    /**
     * Maps an unreadable request body to the right status. A body carrying the derived
     * {@code wbsCode} property (rejected because {@code fail-on-unknown-properties} is on) is a
     * client attempt to write a server-derived field: that maps to {@code 422} (US22.4.1a error AC),
     * distinct from a merely malformed body which stays a {@code 400}.
     *
     * @param ex the thrown exception (Jackson binding failure wrapped by Spring)
     * @return {@code 422} when the offending property is {@code wbsCode}, else {@code 400}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<WbsApiError> handleUnreadableBody(final HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof final PropertyBindingException binding
                && "wbsCode".equals(binding.getPropertyName())) {
            return ResponseEntity.unprocessableEntity().body(new WbsApiError(
                    DerivedFieldNotEditableException.CODE,
                    DerivedFieldNotEditableException.clientSuppliedWbsCode().getMessage()));
        }
        return ResponseEntity.badRequest().body(new WbsApiError("MALFORMED_BODY", "Request body is not readable"));
    }
}

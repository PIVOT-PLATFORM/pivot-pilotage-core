package fr.pivot.pilotage.calendar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the {@code fr.pivot.pilotage.calendar} package's domain exceptions to HTTP responses for
 * {@link CalendarController} (US22.4.5).
 *
 * <p>Scoped to this package only ({@code basePackages}) — mirrors
 * {@code fr.pivot.pilotage.gantt.WbsExceptionHandler}'s package-scoped advice so this controller's
 * error mapping never surprises another package's controller.
 */
@RestControllerAdvice(basePackages = "fr.pivot.pilotage.calendar")
class CalendarExceptionHandler {

    /**
     * Maps an unresolved calendar/exception (unknown tenant/team/calendar, or cross-tenant/team) to a
     * bodyless {@code 404} — non-disclosure posture (CLAUDE.md §Isolation tenant).
     *
     * @param ex the thrown exception
     * @return a bodyless 404 response
     */
    @ExceptionHandler(CalendarNotFoundException.class)
    ResponseEntity<Void> handleNotFound(final CalendarNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Maps a denied write (role gate) to a bodyless {@code 403}.
     *
     * @param ex the thrown exception
     * @return a bodyless 403 response
     */
    @ExceptionHandler(CalendarEditForbiddenException.class)
    ResponseEntity<Void> handleForbidden(final CalendarEditForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Maps a semantically invalid calendar/exception payload (end before start, malformed range) to
     * {@code 422 Unprocessable Entity} with an explicit message (error AC).
     *
     * @param ex the thrown exception
     * @return a 422 response carrying a {@link CalendarApiError} body
     */
    @ExceptionHandler(InvalidCalendarException.class)
    ResponseEntity<CalendarApiError> handleInvalid(final InvalidCalendarException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new CalendarApiError(InvalidCalendarException.CODE, ex.getMessage()));
    }
}

package fr.pivot.pilotage.calendar;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing working-time calendar management (F22.4 — US22.4.5 «&nbsp;Calendriers
 * ouvrés &amp; exceptions&nbsp;»). Thin by design (CLAUDE.md §Standards): the role gate is delegated
 * to {@link CalendarEditPolicy} and every operation to {@link CalendarService}; errors are mapped by
 * {@link CalendarExceptionHandler}.
 *
 * <p><strong>URL shape — gap-era, mirrors {@code WbsTaskController}.</strong>
 * {@code pivot-core-starter} (TenantContext) is not published (CLAUDE.md §gap, TODO-SETUP §5), so
 * {@code tenantId}/{@code teamId} are explicit path variables here too — never a body/query param.
 * Once the starter is consumable they move to the security context with no change to
 * {@link CalendarService}. Calendars are tenant/team-scoped resources (a calendar may carry a
 * {@code null} project id), hence the two-segment prefix
 * {@code /tenants/{tenantId}/teams/{teamId}/calendars}; the effective-calendar resolution, which
 * needs a project and task, is exposed under the project/task path.
 *
 * <p><strong>Contract</strong>
 * <ul>
 *   <li>{@code GET    .../calendars} — list the tenant/team's calendars.</li>
 *   <li>{@code POST   .../calendars} — create a calendar (write, gated).</li>
 *   <li>{@code GET    .../calendars/{calendarId}} — read one calendar.</li>
 *   <li>{@code PUT    .../calendars/{calendarId}} — update a calendar (write, gated).</li>
 *   <li>{@code DELETE .../calendars/{calendarId}} — delete a calendar (write, gated).</li>
 *   <li>{@code GET    .../calendars/{calendarId}/exceptions} — list a calendar's exceptions.</li>
 *   <li>{@code POST   .../calendars/{calendarId}/exceptions} — add a derogatory interval (write, gated).</li>
 *   <li>{@code DELETE .../calendars/{calendarId}/exceptions/{exceptionId}} — remove one (write, gated).</li>
 *   <li>{@code GET    .../projects/{projectId}/tasks/{taskId}/effective-calendar} — resolve the
 *       effective calendar (resource&gt;task&gt;project, D7).</li>
 * </ul>
 */
@RestController
@RequestMapping("/tenants/{tenantId}/teams/{teamId}")
public class CalendarController {

    private final CalendarService calendarService;
    private final CalendarEditPolicy editPolicy;

    /**
     * Constructs the controller.
     *
     * @param calendarService the calendar business logic
     * @param editPolicy      the role-gate extension point for writes (deny-all until the starter
     *                        publishes membership, mirrors {@code WbsEditPolicy})
     */
    public CalendarController(final CalendarService calendarService, final CalendarEditPolicy editPolicy) {
        this.calendarService = calendarService;
        this.editPolicy = editPolicy;
    }

    /**
     * Lists the tenant/team's calendars.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param teamId   the team's {@code public.teams.id}
     * @return {@code 200 OK} with the calendars
     */
    @GetMapping("/calendars")
    public ResponseEntity<List<CalendarResponse>> list(@PathVariable final long tenantId,
            @PathVariable final long teamId) {
        return ResponseEntity.ok(calendarService.list(tenantId, teamId));
    }

    /**
     * Reads a single calendar.
     *
     * @param tenantId   the tenant's {@code public.tenants.id}
     * @param teamId     the team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @return {@code 200 OK} with the calendar; {@code 404} if not visible
     */
    @GetMapping("/calendars/{calendarId}")
    public ResponseEntity<CalendarResponse> read(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long calendarId) {
        return ResponseEntity.ok(calendarService.read(tenantId, teamId, calendarId));
    }

    /**
     * Creates a calendar.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param teamId   the team's {@code public.teams.id}
     * @param request  the creation payload
     * @return {@code 201 Created} with the created calendar; {@code 403} if unauthorized; {@code 404}
     *         if a supplied project is not visible; {@code 422} if the definition is invalid
     */
    @PostMapping("/calendars")
    public ResponseEntity<CalendarResponse> create(@PathVariable final long tenantId,
            @PathVariable final long teamId, @Valid @RequestBody final CreateCalendarRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(calendarService.create(tenantId, teamId, request));
    }

    /**
     * Updates a calendar's name, working days and ranges.
     *
     * @param tenantId   the tenant's {@code public.tenants.id}
     * @param teamId     the team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @param request    the update payload
     * @return {@code 200 OK} with the updated calendar; {@code 403} if unauthorized; {@code 404} if
     *         not visible; {@code 422} if the definition is invalid
     */
    @PutMapping("/calendars/{calendarId}")
    public ResponseEntity<CalendarResponse> update(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long calendarId,
            @Valid @RequestBody final UpdateCalendarRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(calendarService.update(tenantId, teamId, calendarId, request));
    }

    /**
     * Deletes a calendar.
     *
     * @param tenantId   the tenant's {@code public.tenants.id}
     * @param teamId     the team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @return {@code 204 No Content}; {@code 403} if unauthorized; {@code 404} if not visible
     */
    @DeleteMapping("/calendars/{calendarId}")
    public ResponseEntity<Void> delete(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long calendarId) {
        requireEditAuthorized();
        calendarService.delete(tenantId, teamId, calendarId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists a calendar's exceptions.
     *
     * @param tenantId   the tenant's {@code public.tenants.id}
     * @param teamId     the team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @return {@code 200 OK} with the exception days; {@code 404} if the calendar is not visible
     */
    @GetMapping("/calendars/{calendarId}/exceptions")
    public ResponseEntity<List<CalendarExceptionResponse>> listExceptions(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long calendarId) {
        return ResponseEntity.ok(calendarService.listExceptions(tenantId, teamId, calendarId));
    }

    /**
     * Adds a derogatory interval (expanded into one exception per day) to a calendar.
     *
     * @param tenantId   the tenant's {@code public.tenants.id}
     * @param teamId     the team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @param request    the exception payload
     * @return {@code 201 Created} with the created days; {@code 403} if unauthorized; {@code 404} if
     *         the calendar is not visible; {@code 422} if the interval is invalid (end before start)
     */
    @PostMapping("/calendars/{calendarId}/exceptions")
    public ResponseEntity<List<CalendarExceptionResponse>> addException(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long calendarId,
            @Valid @RequestBody final AddExceptionRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(calendarService.addException(tenantId, teamId, calendarId, request));
    }

    /**
     * Removes a single exception day from a calendar.
     *
     * @param tenantId    the tenant's {@code public.tenants.id}
     * @param teamId      the team's {@code public.teams.id}
     * @param calendarId  the calendar id
     * @param exceptionId the exception id
     * @return {@code 204 No Content}; {@code 403} if unauthorized; {@code 404} if not visible
     */
    @DeleteMapping("/calendars/{calendarId}/exceptions/{exceptionId}")
    public ResponseEntity<Void> removeException(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long calendarId,
            @PathVariable final long exceptionId) {
        requireEditAuthorized();
        calendarService.removeException(tenantId, teamId, calendarId, exceptionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves the effective calendar governing a task (optionally for a specific resource), applying
     * the resource&gt;task&gt;project priority (decision D7). A read operation — not gated by the edit
     * policy, only by tenant/team/project isolation.
     *
     * @param tenantId    the tenant's {@code public.tenants.id}
     * @param teamId      the team's {@code public.teams.id}
     * @param projectId   the owning project id
     * @param taskId      the task id
     * @param resourceRef the resource whose calendar may prime, or {@code null}
     * @return {@code 200 OK} with the effective calendar and the level it was resolved from;
     *         {@code 404} if the project/task is not visible or no calendar resolves
     */
    @GetMapping("/projects/{projectId}/tasks/{taskId}/effective-calendar")
    public ResponseEntity<EffectiveCalendarResponse> effectiveCalendar(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId,
            @RequestParam(name = "resourceRef", required = false) final String resourceRef) {
        return ResponseEntity.ok(
                calendarService.resolveEffective(tenantId, teamId, projectId, taskId, resourceRef));
    }

    /**
     * Short-circuits every write endpoint before any service call when the caller is not authorized
     * (security AC — fail-closed today, see {@link DenyAllCalendarEditPolicy}).
     *
     * @throws CalendarEditForbiddenException if the current caller is not authorized
     */
    private void requireEditAuthorized() {
        if (!editPolicy.isAuthorized()) {
            throw new CalendarEditForbiddenException();
        }
    }
}

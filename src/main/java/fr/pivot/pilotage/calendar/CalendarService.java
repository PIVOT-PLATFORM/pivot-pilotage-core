package fr.pivot.pilotage.calendar;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarException;
import fr.pivot.pilotage.schedule.CalendarExceptionRepository;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing {@link CalendarController} — the management of working-time calendars and
 * their exceptions (US22.4.5), plus the effective-calendar resolution contract.
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> The projection of working days / exceptions /
 * working-time onto the worked-minute axis is owned by the pure engine
 * ({@code fr.pivot.pilotage.schedule.engine.WorkingCalendar}) and consumed by {@code SchedulingService}
 * at task&gt;project priority; this service does not re-implement it. It reuses the persisted
 * {@code working_time} JSONB convention ({@code {"ranges":[["HH:00","HH:00"]]}}, whole hours) and adds
 * (a) the CRUD surface the engine lacks, and (b) the <em>resource&nbsp;&gt;&nbsp;task&nbsp;&gt;&nbsp;project</em>
 * resolution (decision D7) the engine cannot express without an assignment.
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and TODO-SETUP §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId} are
 * explicit arguments, never taken from a request body. Every calendar/exception is resolved through a
 * single tenant+team-scoped lookup collapsing every isolation failure into one non-disclosing
 * {@link CalendarNotFoundException} (404).
 */
@Service
public class CalendarService {

    /** ISO week has seven days (Monday=1 .. Sunday=7). */
    private static final int DAYS_IN_WEEK = 7;

    /** Guard against an unbounded interval expansion (a calendar exception spanning ten years). */
    private static final long MAX_EXCEPTION_SPAN_DAYS = 3660L;

    private final ProjectRepository projectRepository;
    private final CalendarRepository calendarRepository;
    private final CalendarExceptionRepository exceptionRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;

    /**
     * Constructs the service.
     *
     * @param projectRepository   tenant/team-scoped project repository (EN18.1) — default calendar
     * @param calendarRepository  tenant/team-scoped calendar repository (EN22.1a)
     * @param exceptionRepository tenant/team-scoped calendar-exception repository (EN22.1a)
     * @param taskRepository      tenant/team-scoped task repository (task-level calendar override)
     * @param assignmentRepository tenant/team-scoped assignment repository (resource resolution)
     */
    public CalendarService(final ProjectRepository projectRepository,
            final CalendarRepository calendarRepository,
            final CalendarExceptionRepository exceptionRepository,
            final TaskRepository taskRepository,
            final AssignmentRepository assignmentRepository) {
        this.projectRepository = projectRepository;
        this.calendarRepository = calendarRepository;
        this.exceptionRepository = exceptionRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
    }

    // ---- calendar CRUD --------------------------------------------------------------------------

    /**
     * Lists every calendar visible to the tenant/team, ordered by id.
     *
     * @param tenantId the requesting tenant's {@code public.tenants.id}
     * @param teamId   the requesting team's {@code public.teams.id}
     * @return the calendars (possibly empty)
     */
    @Transactional(readOnly = true)
    public List<CalendarResponse> list(final long tenantId, final long teamId) {
        return calendarRepository.findAllByTenantIdAndTeamId(tenantId, teamId).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(CalendarService::toResponse)
                .toList();
    }

    /**
     * Reads a single calendar within the tenant/team boundary.
     *
     * @param tenantId   the requesting tenant's {@code public.tenants.id}
     * @param teamId     the requesting team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @return the calendar
     * @throws CalendarNotFoundException if the calendar is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public CalendarResponse read(final long tenantId, final long teamId, final long calendarId) {
        return toResponse(requireCalendar(tenantId, teamId, calendarId));
    }

    /**
     * Creates a calendar. When a {@code projectId} is supplied it is verified within the tenant/team
     * boundary; a {@code null} project id makes a reusable tenant/base calendar.
     *
     * @param tenantId the requesting tenant's {@code public.tenants.id}
     * @param teamId   the requesting team's {@code public.teams.id}
     * @param request  the creation payload
     * @return the created calendar
     * @throws CalendarNotFoundException if a supplied project is not visible to the tenant/team
     * @throws InvalidCalendarException  if the working days or ranges are invalid
     */
    @Transactional
    public CalendarResponse create(final long tenantId, final long teamId, final CreateCalendarRequest request) {
        if (request.projectId() != null) {
            requireProject(tenantId, teamId, request.projectId());
        }
        final short mask = toMask(request.workingDays());
        final String workingTime = toWorkingTimeJson(request.ranges());
        final Calendar calendar = new Calendar(tenantId, teamId, request.projectId(), request.scope(),
                request.name(), mask, workingTime);
        return toResponse(calendarRepository.save(calendar));
    }

    /**
     * Updates a calendar's name, working days and working ranges.
     *
     * @param tenantId   the requesting tenant's {@code public.tenants.id}
     * @param teamId     the requesting team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @param request    the update payload
     * @return the updated calendar
     * @throws CalendarNotFoundException if the calendar is not visible to the tenant/team
     * @throws InvalidCalendarException  if the working days or ranges are invalid
     */
    @Transactional
    public CalendarResponse update(final long tenantId, final long teamId, final long calendarId,
            final UpdateCalendarRequest request) {
        final Calendar calendar = requireCalendar(tenantId, teamId, calendarId);
        calendar.setName(request.name());
        calendar.setWorkingDaysMask(toMask(request.workingDays()));
        calendar.setWorkingTime(toWorkingTimeJson(request.ranges()));
        return toResponse(calendarRepository.save(calendar));
    }

    /**
     * Deletes a calendar (its exceptions cascade via the DB FK).
     *
     * @param tenantId   the requesting tenant's {@code public.tenants.id}
     * @param teamId     the requesting team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @throws CalendarNotFoundException if the calendar is not visible to the tenant/team
     */
    @Transactional
    public void delete(final long tenantId, final long teamId, final long calendarId) {
        final Calendar calendar = requireCalendar(tenantId, teamId, calendarId);
        calendarRepository.delete(calendar);
    }

    // ---- exceptions -----------------------------------------------------------------------------

    /**
     * Lists a calendar's exceptions, ordered by date.
     *
     * @param tenantId   the requesting tenant's {@code public.tenants.id}
     * @param teamId     the requesting team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @return the exception days (possibly empty)
     * @throws CalendarNotFoundException if the calendar is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public List<CalendarExceptionResponse> listExceptions(final long tenantId, final long teamId,
            final long calendarId) {
        requireCalendar(tenantId, teamId, calendarId);
        return exceptionRepository.findAllByCalendarIdAndTenantIdAndTeamId(calendarId, tenantId, teamId).stream()
                .sorted((a, b) -> a.getExceptionDate().compareTo(b.getExceptionDate()))
                .map(CalendarService::toExceptionResponse)
                .toList();
    }

    /**
     * Adds a derogatory interval to a calendar, expanded into one exception row per day
     * ({@code startDate}..{@code endDate}, both inclusive).
     *
     * @param tenantId   the requesting tenant's {@code public.tenants.id}
     * @param teamId     the requesting team's {@code public.teams.id}
     * @param calendarId the calendar id
     * @param request    the exception payload
     * @return the created exception days, ascending by date
     * @throws CalendarNotFoundException if the calendar is not visible to the tenant/team
     * @throws InvalidCalendarException  if the interval end is before its start, or ranges are invalid
     */
    @Transactional
    public List<CalendarExceptionResponse> addException(final long tenantId, final long teamId,
            final long calendarId, final AddExceptionRequest request) {
        requireCalendar(tenantId, teamId, calendarId);
        if (request.endDate().isBefore(request.startDate())) {
            throw InvalidCalendarException.endBeforeStart(request.startDate(), request.endDate());
        }
        final long span = request.startDate().until(request.endDate()).getDays()
                + monthsToDaysGuard(request);
        if (span > MAX_EXCEPTION_SPAN_DAYS) {
            throw new InvalidCalendarException("calendar exception interval spans more than "
                    + MAX_EXCEPTION_SPAN_DAYS + " days");
        }
        final String workingTime = request.working() && !request.ranges().isEmpty()
                ? toWorkingTimeJson(request.ranges()) : null;

        final List<CalendarException> created = new ArrayList<>();
        for (LocalDate d = request.startDate(); !d.isAfter(request.endDate()); d = d.plusDays(1)) {
            created.add(exceptionRepository.save(new CalendarException(tenantId, teamId, calendarId,
                    d, request.working(), workingTime)));
        }
        return created.stream().map(CalendarService::toExceptionResponse).toList();
    }

    /**
     * Removes a single exception day from a calendar.
     *
     * @param tenantId    the requesting tenant's {@code public.tenants.id}
     * @param teamId      the requesting team's {@code public.teams.id}
     * @param calendarId  the parent calendar id
     * @param exceptionId the exception id
     * @throws CalendarNotFoundException if the calendar or the exception is not visible to the scope
     */
    @Transactional
    public void removeException(final long tenantId, final long teamId, final long calendarId,
            final long exceptionId) {
        requireCalendar(tenantId, teamId, calendarId);
        final CalendarException ex = exceptionRepository
                .findByIdAndCalendarIdAndTenantIdAndTeamId(exceptionId, calendarId, tenantId, teamId)
                .orElseThrow(() -> new CalendarNotFoundException(calendarId, tenantId, teamId));
        exceptionRepository.delete(ex);
    }

    // ---- effective-calendar resolution (decision D7) --------------------------------------------

    /**
     * Resolves the calendar that governs a task's working time, applying the priority
     * <strong>resource&nbsp;&gt;&nbsp;task&nbsp;&gt;&nbsp;project</strong> (EN22.1, decision D7):
     * <ol>
     *   <li>a {@code RESOURCE}-scoped calendar named after {@code resourceRef} (when supplied and
     *       matched to an assignment on the task) wins;</li>
     *   <li>else the task's own {@code calendar_id} override wins;</li>
     *   <li>else the project's default calendar — the always-present fallback.</li>
     * </ol>
     *
     * @param tenantId    the requesting tenant's {@code public.tenants.id}
     * @param teamId      the requesting team's {@code public.teams.id}
     * @param projectId   the owning project id
     * @param taskId      the task whose effective calendar is resolved
     * @param resourceRef the resource whose calendar may prime, or {@code null} for the task/project
     *                    resolution only
     * @return the effective calendar and the level it was resolved from
     * @throws CalendarNotFoundException if the project/task is not visible, or no calendar resolves
     */
    @Transactional(readOnly = true)
    public EffectiveCalendarResponse resolveEffective(final long tenantId, final long teamId,
            final long projectId, final long taskId, final String resourceRef) {
        final Project project = requireProject(tenantId, teamId, projectId);
        final Task task = taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(taskId, projectId, tenantId, teamId)
                .orElseThrow(() -> new CalendarNotFoundException(projectId, tenantId, teamId));

        final Optional<Calendar> resourceCalendar = resolveResourceCalendar(tenantId, teamId, task, resourceRef);
        if (resourceCalendar.isPresent()) {
            return toEffective(CalendarScope.RESOURCE, resourceCalendar.get());
        }
        if (task.getCalendarId() != null) {
            final Calendar taskCalendar = requireCalendar(tenantId, teamId, task.getCalendarId());
            return toEffective(CalendarScope.TASK, taskCalendar);
        }
        if (project.getCalendar() != null) {
            final Calendar projectCalendar = requireCalendar(tenantId, teamId, project.getCalendar().getId());
            return toEffective(CalendarScope.PROJECT, projectCalendar);
        }
        throw new CalendarNotFoundException(projectId, tenantId, teamId);
    }

    /**
     * Finds the {@code RESOURCE}-scoped calendar priming for {@code resourceRef} on {@code task}: the
     * resource must actually be assigned to the task, and a resource calendar whose {@code name}
     * equals {@code resourceRef} must be visible to the tenant/team (the resource↔calendar convention,
     * reusing existing columns — no schema change). Returns empty when either is absent.
     */
    private Optional<Calendar> resolveResourceCalendar(final long tenantId, final long teamId,
            final Task task, final String resourceRef) {
        if (resourceRef == null || resourceRef.isBlank()) {
            return Optional.empty();
        }
        final boolean assigned = assignmentRepository
                .findAllByTaskIdAndTenantIdAndTeamId(task.getId(), tenantId, teamId).stream()
                .map(Assignment::getResourceRef)
                .anyMatch(resourceRef::equals);
        if (!assigned) {
            return Optional.empty();
        }
        return calendarRepository.findAllByTenantIdAndTeamId(tenantId, teamId).stream()
                .filter(c -> c.getScope() == CalendarScope.RESOURCE && resourceRef.equals(c.getName()))
                .findFirst();
    }

    // ---- shared guards --------------------------------------------------------------------------

    private Project requireProject(final long tenantId, final long teamId, final long projectId) {
        return projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new CalendarNotFoundException(projectId, tenantId, teamId));
    }

    private Calendar requireCalendar(final long tenantId, final long teamId, final long calendarId) {
        return calendarRepository.findByIdAndTenantIdAndTeamId(calendarId, tenantId, teamId)
                .orElseThrow(() -> new CalendarNotFoundException(calendarId, tenantId, teamId));
    }

    // ---- mapping helpers ------------------------------------------------------------------------

    private static EffectiveCalendarResponse toEffective(final CalendarScope from, final Calendar c) {
        return new EffectiveCalendarResponse(c.getId(), from, toResponse(c));
    }

    private static CalendarResponse toResponse(final Calendar c) {
        return new CalendarResponse(c.getId(), c.getProjectId(), c.getScope(), c.getName(),
                fromMask(c.getWorkingDaysMask()), parseRanges(c.getWorkingTime()));
    }

    private static CalendarExceptionResponse toExceptionResponse(final CalendarException e) {
        final List<WorkingTimeRange> ranges = Boolean.TRUE.equals(e.getWorking())
                ? parseRanges(e.getWorkingTime()) : List.of();
        return new CalendarExceptionResponse(e.getId(), e.getCalendarId(), e.getExceptionDate(),
                Boolean.TRUE.equals(e.getWorking()), ranges);
    }

    /**
     * Encodes the ISO working days (1=Mon..7=Sun) into the Mon..Sun bitmask (bit 0=Monday), rejecting
     * out-of-range values so an invalid day cannot corrupt the stored mask.
     */
    private static short toMask(final List<Integer> workingDays) {
        int mask = 0;
        for (final Integer day : workingDays) {
            if (day == null || day < 1 || day > DAYS_IN_WEEK) {
                throw new InvalidCalendarException("working day out of range (1..7): " + day);
            }
            mask |= 1 << (day - 1);
        }
        if (mask == 0) {
            throw new InvalidCalendarException("a calendar must have at least one working day");
        }
        return (short) mask;
    }

    private static List<Integer> fromMask(final Short mask) {
        final List<Integer> days = new ArrayList<>();
        final int m = mask == null ? 0 : mask;
        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            if ((m & (1 << i)) != 0) {
                days.add(i + 1);
            }
        }
        return days;
    }

    /**
     * Serialises whole-hour ranges into the persisted {@code working_time} JSONB, reusing the existing
     * convention {@code {"ranges":[["HH:00","HH:00"]]}} the engine parser
     * ({@code CalendarWorkingTime}) consumes. Rejects a range whose end is not strictly after its
     * start (whole-hour alignment is already guaranteed by the {@code HH:00} form).
     */
    private static String toWorkingTimeJson(final List<WorkingTimeRange> ranges) {
        final String body = ranges.stream()
                .map(CalendarService::toJsonRange)
                .collect(Collectors.joining(","));
        return "{\"ranges\":[" + body + "]}";
    }

    private static String toJsonRange(final WorkingTimeRange r) {
        if (r.endHour() <= r.startHour()) {
            throw new InvalidCalendarException(
                    "working range end hour " + r.endHour() + " must be after start hour " + r.startHour());
        }
        return "[\"" + twoDigits(r.startHour()) + ":00\",\"" + twoDigits(r.endHour()) + ":00\"]";
    }

    private static String twoDigits(final int hour) {
        return hour < 10 ? "0" + hour : Integer.toString(hour);
    }

    /**
     * Parses the persisted {@code working_time} JSONB back into whole-hour ranges for a response.
     * Reuses the engine's {@code {"ranges":[["HH:00","HH:00"]]}} convention; a blank/empty payload
     * yields an empty list.
     */
    private static List<WorkingTimeRange> parseRanges(final String json) {
        final List<WorkingTimeRange> ranges = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return ranges;
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[\\s*\"(\\d{2}):\\d{2}\"\\s*,\\s*\"(\\d{2}):\\d{2}\"\\s*\\]")
                .matcher(json);
        while (m.find()) {
            ranges.add(new WorkingTimeRange(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        return ranges;
    }

    /**
     * Adds the whole-month component of an interval spanning more than a month to the day guard, so
     * {@link #MAX_EXCEPTION_SPAN_DAYS} is compared against the real day count rather than only the
     * intra-month remainder returned by {@code Period.getDays()}.
     */
    private static long monthsToDaysGuard(final AddExceptionRequest request) {
        final java.time.Period p = request.startDate().until(request.endDate());
        return (long) p.getYears() * 366L + (long) p.getMonths() * 31L;
    }
}

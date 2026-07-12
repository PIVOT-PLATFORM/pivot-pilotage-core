package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.SchedulingMode;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.engine.WorkingCalendar;
import fr.pivot.pilotage.schedule.service.SchedulingService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing the US22.4.6 surface of {@link WbsTaskController} &mdash; «&nbsp;jalons
 * &amp; tâches périodiques&nbsp;». Owns the creation of a periodic task series: one {@code Task} row
 * with {@code node_kind=RECURRING} carrying the persisted recurrence rule (EN22.1a frozen contract),
 * plus its generated occurrences as ordinary child {@code Task} rows &mdash; {@code LEAF}, or
 * {@code MILESTONE} when the occurrence duration is zero, the same auto-classification as the
 * US22.4.6 AC1 duration-0 rule ({@link WbsTaskService#createTask}), reused verbatim.
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> This service owns none of the calendar maths:
 * shifting an occurrence off a non-working day (US22.4.5) is delegated to
 * {@link WorkingCalendar#snapForward(Instant)} / {@link WorkingCalendar#advance(Instant, long)}
 * (EN22.1b), reached via {@link SchedulingService#defaultCalendar(long, long)} rather than
 * re-deriving calendar loading here. After persisting the series and its occurrences it invokes
 * {@link SchedulingService#scheduleProject(long, long)} <strong>once</strong> &mdash; never once per
 * occurrence &mdash; so the WBS numbering is re-derived in a single CPM pass, and reads the created
 * nodes back through {@code WbsTaskService.tree} so they carry the identical server-derived WBS
 * code/ARIA/A11y label as any other tree node.
 *
 * <p><strong>Single audit trace for the whole batch (US22.4.6 security AC).</strong> Exactly one
 * structured log line is emitted per call, regardless of {@code occurrenceCount} &mdash; generating N
 * occurrences is one state-changing action, not N (mirrors {@link TaskEffortService}'s
 * {@code event=...} convention, CLAUDE.md &sect;Standards "toute action state-changing &rarr; log
 * structuré JSON").
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md &sect;gap and TODO-SETUP &sect;5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId} are
 * explicit arguments, never taken from a request body. The target project/parent are resolved through
 * a tenant+team-scoped lookup collapsing every isolation failure into one non-disclosing
 * {@link WbsProjectNotFoundException}/{@link WbsTaskNotFoundException} (404).
 */
@Service
public class RecurringTaskService {

    private static final Logger LOG = LoggerFactory.getLogger(RecurringTaskService.class);

    /** Series/occurrences are day-grained (matches the Gantt WBS default, US22.4.1a). */
    private static final TemporalPrecision PRECISION = TemporalPrecision.DAY;

    /** Initial revision of every freshly created row. */
    private static final int INITIAL_REVISION = 0;

    /** Default cadence multiplier when the caller omits {@code intervalCount}. */
    private static final int DEFAULT_INTERVAL = 1;

    /**
     * Maximum number of occurrences generated in a single call (backlog note: "prévoir une limite
     * raisonnable&hellip; pour éviter une explosion du graphe", EN22.2 perf). 500 comfortably covers
     * the backlog's own example (a weekly committee over several years &mdash; 500 weeks &#8776; 9.6
     * years) while bounding the worst case (e.g. a daily recurrence) to a graph growth the engine's
     * CPM pass ({@link SchedulingService#scheduleProject}) stays well within.
     */
    public static final int MAX_OCCURRENCES = 500;

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final SchedulingService schedulingService;
    private final WbsTaskService wbsTaskService;

    /**
     * Constructs the service.
     *
     * @param projectRepository tenant/team-scoped project repository (isolation boundary)
     * @param taskRepository    tenant/team-scoped task repository (series + occurrences persistence)
     * @param schedulingService the EN22.1b engine service &mdash; CPM recompute and calendar
     *                          resolution
     * @param wbsTaskService    reused to render the created nodes with their server-derived WBS
     *                          code/ARIA attributes (Étape 0, avoids duplicating the tree walk)
     */
    public RecurringTaskService(final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final SchedulingService schedulingService, final WbsTaskService wbsTaskService) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.schedulingService = schedulingService;
        this.wbsTaskService = wbsTaskService;
    }

    /**
     * Creates a periodic task series and materialises its occurrences.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the creation payload
     * @return the created series and its generated occurrences
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if {@code request.parentTaskId()} does not resolve on this
     *                                     project
     * @throws InvalidRecurrenceException  if the frequency or occurrence count is missing/invalid, or
     *                                     exceeds {@link #MAX_OCCURRENCES}
     */
    @Transactional
    public RecurringTaskResponse createRecurringTask(final long tenantId, final long teamId, final long projectId,
            final CreateRecurringTaskRequest request) {
        requireProject(tenantId, teamId, projectId);
        validateRecurrence(request);

        final Long parentId = request.parentTaskId();
        if (parentId != null) {
            promoteToSummary(requireTask(tenantId, teamId, projectId, parentId));
        }

        final int intervalCount = request.intervalCount() != null ? request.intervalCount() : DEFAULT_INTERVAL;
        final String recurrenceRule = buildRecurrenceRule(request.frequency(), intervalCount,
                request.occurrenceCount(), request.firstOccurrenceDate());

        final Task series = new Task(tenantId, teamId, projectId,
                nextPosition(tenantId, teamId, projectId, parentId), request.name(), NodeKind.RECURRING,
                Boolean.FALSE, PRECISION, INITIAL_REVISION);
        series.setParentTaskId(parentId);
        series.setRecurrenceRule(recurrenceRule);
        series.setSchedulingMode(SchedulingMode.MANUAL);
        final Task savedSeries = taskRepository.save(series);

        final WorkingCalendar calendar = schedulingService.defaultCalendar(projectId, tenantId);
        final List<Task> occurrences = generateOccurrences(tenantId, teamId, projectId, savedSeries.getId(),
                request, intervalCount, calendar);
        taskRepository.saveAll(occurrences);
        restateSeriesSpan(savedSeries, occurrences);

        schedulingService.scheduleProject(projectId, tenantId);

        LOG.info("event=recurring_task_created tenant={} team={} project={} seriesTask={} frequency={} "
                        + "intervalCount={} occurrenceCount={} generatedOccurrences={}",
                tenantId, teamId, projectId, savedSeries.getId(), request.frequency(), intervalCount,
                request.occurrenceCount(), occurrences.size());

        return buildResponse(tenantId, teamId, projectId, savedSeries.getId(), recurrenceRule, occurrences);
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * Validates the recurrence definition (US22.4.6 error AC): both {@code frequency} and a strictly
     * positive {@code occurrenceCount} are mandatory, and the count must not exceed
     * {@link #MAX_OCCURRENCES}.
     */
    private void validateRecurrence(final CreateRecurringTaskRequest request) {
        if (request.frequency() == null || request.occurrenceCount() == null || request.occurrenceCount() <= 0) {
            throw InvalidRecurrenceException.missingFrequencyOrOccurrenceCount();
        }
        if (request.occurrenceCount() > MAX_OCCURRENCES) {
            throw InvalidRecurrenceException.tooManyOccurrences(request.occurrenceCount(), MAX_OCCURRENCES);
        }
    }

    /**
     * Builds every occurrence row (not yet persisted): the naive calendar date is derived from the
     * frequency/interval, then snapped into working time (US22.4.5) before the duration (if any) is
     * projected onto the calendar.
     */
    private List<Task> generateOccurrences(final long tenantId, final long teamId, final long projectId,
            final long seriesId, final CreateRecurringTaskRequest request, final int intervalCount,
            final WorkingCalendar calendar) {
        final int durationMinutes = request.durationMinutes() != null ? request.durationMinutes() : 0;
        final NodeKind occurrenceKind = durationMinutes == 0 ? NodeKind.MILESTONE : NodeKind.LEAF;
        final int count = request.occurrenceCount();

        final List<Task> occurrences = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final LocalDate naiveDate = shift(request.firstOccurrenceDate(), request.frequency(), intervalCount, i);
            final Instant anchor = naiveDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            // Respect the working calendar (US22.4.5): a non-working anchor shifts forward exactly
            // like any other task's start (WorkingCalendar.snapForward) — never a bespoke rule here.
            final Instant start = calendar.snapForward(anchor);
            final Instant finish = durationMinutes > 0 ? calendar.advance(start, durationMinutes) : start;

            final Task occurrence = new Task(tenantId, teamId, projectId, i,
                    occurrenceName(request.name(), i, count), occurrenceKind, Boolean.FALSE, PRECISION,
                    INITIAL_REVISION);
            occurrence.setParentTaskId(seriesId);
            occurrence.setDurationMinutes(durationMinutes);
            occurrence.setStartDate(start);
            occurrence.setFinishDate(finish);
            occurrence.setSchedulingMode(SchedulingMode.MANUAL);
            occurrences.add(occurrence);
        }
        return occurrences;
    }

    /**
     * Names one occurrence from its series (A11y &mdash; an explicit text label distinguishing
     * occurrences from one another, never relying on the diamond glyph/colour alone).
     */
    private static String occurrenceName(final String seriesName, final int index, final int count) {
        return seriesName + " — occurrence " + (index + 1) + "/" + count;
    }

    /** Computes occurrence {@code occurrenceIndex}'s naive (pre-calendar-shift) calendar date. */
    private static LocalDate shift(final LocalDate first, final RecurrenceFrequency frequency,
            final int intervalCount, final int occurrenceIndex) {
        final long step = (long) intervalCount * occurrenceIndex;
        return switch (frequency) {
            case DAILY -> first.plusDays(step);
            case WEEKLY -> first.plusWeeks(step);
            case MONTHLY -> first.plusMonths(step);
        };
    }

    /**
     * Builds the persisted {@code recurrence_rule} (an iCalendar-style, not RFC 5545-strict, string
     * &mdash; the frozen EN22.1a contract only requires {@code TEXT}, no parser reads it back today).
     */
    private static String buildRecurrenceRule(final RecurrenceFrequency frequency, final int intervalCount,
            final int occurrenceCount, final LocalDate firstOccurrenceDate) {
        return "FREQ=" + frequency + ";INTERVAL=" + intervalCount + ";COUNT=" + occurrenceCount
                + ";DTSTART=" + firstOccurrenceDate;
    }

    /**
     * Restates the series' own span as the first&rarr;last occurrence bounds &mdash; a snapshot, not
     * a live aggregate: the series is {@code RECURRING}, not {@code SUMMARY}, so it does not
     * auto-recompute on a later occurrence edit (bulk-editing a series is explicitly "Hors périmètre"
     * per the backlog note).
     */
    private void restateSeriesSpan(final Task series, final List<Task> occurrences) {
        if (occurrences.isEmpty()) {
            return;
        }
        series.setStartDate(occurrences.get(0).getStartDate());
        series.setFinishDate(occurrences.get(occurrences.size() - 1).getFinishDate());
        taskRepository.save(series);
    }

    /**
     * Renders the series and its occurrences through {@code WbsTaskService.tree} (Étape 0 reuse) so
     * the response carries the same server-derived WBS code/ARIA/A11y label as any other tree node.
     */
    private RecurringTaskResponse buildResponse(final long tenantId, final long teamId, final long projectId,
            final long seriesId, final String recurrenceRule, final List<Task> occurrences) {
        final Map<Long, WbsTaskResponse> byId = new HashMap<>();
        for (final WbsTaskResponse node : wbsTaskService.tree(tenantId, teamId, projectId).nodes()) {
            byId.put(node.taskId(), node);
        }
        final WbsTaskResponse seriesView = byId.get(seriesId);
        final List<WbsTaskResponse> occurrenceViews = occurrences.stream()
                .map(o -> byId.get(o.getId()))
                .filter(Objects::nonNull)
                .toList();
        return new RecurringTaskResponse(seriesView, recurrenceRule, occurrenceViews);
    }

    /** A node with children is a summary (US22.4.1a) &mdash; same rule {@code WbsTaskService} applies. */
    private void promoteToSummary(final Task task) {
        if (task.getNodeKind() == NodeKind.LEAF) {
            task.setNodeKind(NodeKind.SUMMARY);
            taskRepository.save(task);
        }
    }

    private int nextPosition(final long tenantId, final long teamId, final long projectId, final Long parentId) {
        int max = -1;
        for (final Task t : taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)) {
            if (Objects.equals(t.getParentTaskId(), parentId)) {
                final int pos = t.getPosition() == null ? 0 : t.getPosition();
                if (pos > max) {
                    max = pos;
                }
            }
        }
        return max + 1;
    }

    /**
     * Resolves the target project within the tenant/team boundary &mdash; the single isolation check
     * shared by every operation.
     */
    private void requireProject(final long tenantId, final long teamId, final long projectId) {
        projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsProjectNotFoundException(projectId, tenantId, teamId));
    }

    /** Resolves a task within the project/tenant/team boundary. */
    private Task requireTask(final long tenantId, final long teamId, final long projectId, final long taskId) {
        return taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(taskId, projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsTaskNotFoundException(taskId, projectId));
    }
}

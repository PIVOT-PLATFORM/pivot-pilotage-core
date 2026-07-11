package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.SchedulingMode;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.engine.ManualVariance;
import fr.pivot.pilotage.schedule.engine.ScheduleResult;
import fr.pivot.pilotage.schedule.service.SchedulingService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing the US22.4.2 surface of {@link WbsTaskController} — «&nbsp;durées, effort,
 * planification auto vs manuelle&nbsp;». Owns three levers on a leaf task and, after each, re-runs the
 * CPM through {@link SchedulingService} so AUTO tasks recompute and MANUAL tasks surface their
 * variance:
 * <ul>
 *   <li><strong>duration</strong> — sets {@code duration_minutes} (a milestone may be 0, any other
 *       task must be {@code > 0}; negative is refused);</li>
 *   <li><strong>effort</strong> — sets an assignment's {@code units_percent} and re-derives the
 *       planned {@code work_minutes} from the relation <em>work = duration × units</em> (US22.4.2 AC);
 *       the resource calendar (US22.4.5) being out of scope here, the duration is the project/task
 *       calendar's worked duration, matching the engine's projection;</li>
 *   <li><strong>scheduling mode</strong> — toggles {@code AUTO}/{@code MANUAL}; switching to MANUAL
 *       stores a persistent "manually planned" state (the task's current dates become the pinned
 *       reference) so the engine can report the drift (plannedManual/wouldBeAuto/delta) without ever
 *       overwriting the user's dates.</li>
 * </ul>
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> This service owns none of the scheduling
 * maths: AUTO recompute, MANUAL pinning and the manual variance are all produced by the pure engine
 * ({@code fr.pivot.pilotage.schedule.engine.ScheduleEngine}) driven by {@link SchedulingService}. On
 * every write it persists the mutated row and then invokes
 * {@link SchedulingService#scheduleProject(long, long)} <em>within the same transaction</em>, reading
 * back the fresh {@link ManualVariance} for the mutated task.
 *
 * <p><strong>Error handling (US22.4.2 error AC).</strong> A negative duration, a zero duration on a
 * non-milestone, or a non-positive units value is rejected with {@link InvalidTaskEffortException}
 * (422) <em>before</em> any persistence, so the task keeps its previous values. A write targeting a
 * derived engine field ({@code early_*}/{@code late_*}, slack, {@code is_critical}) is refused
 * upstream at the controller/binding layer (422).
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and TODO-SETUP §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId} are
 * explicit arguments, never taken from a request body. Every project/task is resolved through a
 * tenant+team-scoped lookup collapsing every isolation failure into one non-disclosing
 * {@link WbsProjectNotFoundException}/{@link WbsTaskNotFoundException} (404).
 */
@Service
public class TaskEffortService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskEffortService.class);

    /** Percent basis for the work = duration × units relation (units are a percentage). */
    private static final BigDecimal PERCENT = new BigDecimal("100");

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final SchedulingService schedulingService;

    /**
     * Constructs the service.
     *
     * @param projectRepository    tenant/team-scoped project repository (isolation boundary)
     * @param taskRepository       tenant/team-scoped task repository (target resolution)
     * @param assignmentRepository tenant/team-scoped assignment repository (effort/work, EN22.1a)
     * @param schedulingService    the CPM driver that recomputes and reports variance (EN22.1b)
     */
    public TaskEffortService(final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final AssignmentRepository assignmentRepository, final SchedulingService schedulingService) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.schedulingService = schedulingService;
    }

    /**
     * Sets a task's duration in worked minutes and re-runs the CPM.
     *
     * @param tenantId        the requesting tenant's {@code public.tenants.id}
     * @param teamId          the requesting team's {@code public.teams.id}
     * @param projectId       the project id
     * @param taskId          the task to edit
     * @param durationMinutes the new duration in worked minutes ({@code >= 0}; {@code 0} only for a
     *                        milestone)
     * @return the task's refreshed scheduling state
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if the task does not resolve on this project
     * @throws InvalidTaskEffortException  if the duration is negative, or zero on a non-milestone
     */
    @Transactional
    public TaskSchedulingResponse setDuration(final long tenantId, final long teamId, final long projectId,
            final long taskId, final int durationMinutes) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);

        if (durationMinutes < 0) {
            throw InvalidTaskEffortException.negativeDuration(durationMinutes);
        }
        if (durationMinutes == 0 && task.getNodeKind() != NodeKind.MILESTONE) {
            throw InvalidTaskEffortException.zeroDurationNonMilestone(taskId);
        }

        task.setDurationMinutes(durationMinutes);
        task.setRevision((task.getRevision() == null ? 0 : task.getRevision()) + 1);
        taskRepository.save(task);
        // Duration changed ⇒ dependent work must follow the work = duration × units relation.
        reprojectWork(tenantId, teamId, taskId, durationMinutes);
        LOG.info("event=task_duration_set tenant={} team={} project={} task={} durationMinutes={}",
                tenantId, teamId, projectId, taskId, durationMinutes);
        return recomputeAndDescribe(tenantId, teamId, projectId, task);
    }

    /**
     * Sets the resource units of a task's assignment (creating the assignment if absent) and
     * re-derives the planned work from <em>work = duration × units</em>, then re-runs the CPM.
     *
     * @param tenantId    the requesting tenant's {@code public.tenants.id}
     * @param teamId      the requesting team's {@code public.teams.id}
     * @param projectId   the project id
     * @param taskId      the task to edit
     * @param resourceRef the logical resource the units apply to
     * @param units       the assignment units in percent ({@code > 0})
     * @return the task's refreshed scheduling state (its {@code workMinutes} reflecting the relation)
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if the task does not resolve on this project
     * @throws InvalidTaskEffortException  if {@code units} is not strictly positive
     */
    @Transactional
    public TaskSchedulingResponse setEffort(final long tenantId, final long teamId, final long projectId,
            final long taskId, final String resourceRef, final BigDecimal units) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);

        if (units == null || units.signum() <= 0) {
            throw InvalidTaskEffortException.nonPositiveUnits();
        }

        final Assignment assignment = assignmentRepository.findAllByTaskIdAndTenantIdAndTeamId(taskId, tenantId, teamId)
                .stream()
                .filter(a -> resourceRef.equals(a.getResourceRef()))
                .findFirst()
                .orElseGet(() -> new Assignment(tenantId, teamId, taskId, resourceRef, units));
        assignment.setUnitsPercent(units);
        assignment.setWorkMinutes(workMinutes(task.getDurationMinutes(), units));
        assignmentRepository.save(assignment);

        LOG.info("event=task_effort_set tenant={} team={} project={} task={} resourceRef={} units={} workMinutes={}",
                tenantId, teamId, projectId, taskId, resourceRef, units, assignment.getWorkMinutes());
        return recomputeAndDescribe(tenantId, teamId, projectId, task);
    }

    /**
     * Switches a task between AUTO and MANUAL scheduling and re-runs the CPM. Switching to MANUAL
     * stores the persistent "manually planned" state (the current dates become the pinned reference)
     * so the engine reports the variance instead of overwriting them; switching back to AUTO lets the
     * engine recompute the dates.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to edit
     * @param mode      the target scheduling mode
     * @return the task's refreshed scheduling state (with the manual variance when MANUAL)
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if the task does not resolve on this project
     */
    @Transactional
    public TaskSchedulingResponse setSchedulingMode(final long tenantId, final long teamId, final long projectId,
            final long taskId, final SchedulingMode mode) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);

        task.setSchedulingMode(mode);
        task.setRevision((task.getRevision() == null ? 0 : task.getRevision()) + 1);
        taskRepository.save(task);

        LOG.info("event=task_scheduling_mode_set tenant={} team={} project={} task={} mode={}",
                tenantId, teamId, projectId, taskId, mode);
        return recomputeAndDescribe(tenantId, teamId, projectId, task);
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * Re-runs the whole-project CPM (AUTO tasks recompute, MANUAL variances refresh), reloads the
     * persisted task and folds in its manual variance and total planned work.
     */
    private TaskSchedulingResponse recomputeAndDescribe(final long tenantId, final long teamId,
            final long projectId, final Task task) {
        final ScheduleResult result = schedulingService.scheduleProject(projectId, tenantId);
        final Task fresh = requireTask(tenantId, teamId, projectId, task.getId());
        final ManualVariance variance = variance(result, fresh.getId());
        final SchedulingMode effective = effectiveMode(tenantId, teamId, projectId, fresh);
        final Integer totalWork = totalWorkMinutes(fresh.getId(), tenantId, teamId);

        return new TaskSchedulingResponse(fresh.getId(), fresh.getSchedulingMode(), effective,
                fresh.getDurationMinutes(), totalWork, fresh.getStartDate(), fresh.getFinishDate(),
                variance != null ? variance.plannedManual() : null,
                variance != null ? variance.wouldBeAuto() : null,
                variance != null ? variance.deltaMinutes() : 0L,
                fresh.getRevision() == null ? 0 : fresh.getRevision());
    }

    /** Finds the engine's manual variance for a task, or {@code null} (AUTO tasks have none). */
    private static ManualVariance variance(final ScheduleResult result, final long taskId) {
        for (final ManualVariance v : result.variances()) {
            if (v.taskId() == taskId) {
                return v;
            }
        }
        return null;
    }

    /** Resolves the effective mode: the task's own mode, else the project's. */
    private SchedulingMode effectiveMode(final long tenantId, final long teamId, final long projectId,
            final Task task) {
        if (task.getSchedulingMode() != null) {
            return task.getSchedulingMode();
        }
        return projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .map(Project::getSchedulingMode)
                .orElse(SchedulingMode.AUTO);
    }

    /**
     * Re-derives every assignment's {@code work_minutes} on the task from the new duration so the
     * work = duration × units relation holds after a duration change.
     */
    private void reprojectWork(final long tenantId, final long teamId, final long taskId,
            final int durationMinutes) {
        final List<Assignment> assignments =
                assignmentRepository.findAllByTaskIdAndTenantIdAndTeamId(taskId, tenantId, teamId);
        for (final Assignment a : assignments) {
            a.setWorkMinutes(workMinutes(durationMinutes, a.getUnitsPercent()));
        }
        if (!assignments.isEmpty()) {
            assignmentRepository.saveAll(assignments);
        }
    }

    /** Total planned work over a task's assignments (Σ of work = duration × units), or {@code null}. */
    private Integer totalWorkMinutes(final long taskId, final long tenantId, final long teamId) {
        final List<Assignment> assignments =
                assignmentRepository.findAllByTaskIdAndTenantIdAndTeamId(taskId, tenantId, teamId);
        if (assignments.isEmpty()) {
            return null;
        }
        long sum = 0L;
        for (final Assignment a : assignments) {
            sum += a.getWorkMinutes() != null ? a.getWorkMinutes() : Integer.valueOf(0);
        }
        return Integer.valueOf((int) sum);
    }

    /** Computes {@code work = duration × units%/100}, rounded to the nearest worked minute. */
    private static Integer workMinutes(final Integer durationMinutes, final BigDecimal units) {
        final long duration = durationMinutes != null ? durationMinutes.longValue() : 0L;
        final BigDecimal work = BigDecimal.valueOf(duration)
                .multiply(units)
                .divide(PERCENT, 0, RoundingMode.HALF_UP);
        return Integer.valueOf(work.intValueExact());
    }

    /**
     * Resolves the target project within the tenant/team boundary — the single isolation check
     * shared by every operation.
     */
    private Project requireProject(final long tenantId, final long teamId, final long projectId) {
        return projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsProjectNotFoundException(projectId, tenantId, teamId));
    }

    /** Resolves a task within the project/tenant/team boundary. */
    private Task requireTask(final long tenantId, final long teamId, final long projectId, final long taskId) {
        return taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(taskId, projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsTaskNotFoundException(taskId, projectId));
    }
}

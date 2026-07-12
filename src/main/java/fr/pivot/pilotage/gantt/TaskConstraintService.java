package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.ConstraintType;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskConstraint;
import fr.pivot.pilotage.schedule.TaskConstraintRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.engine.ScheduleResult;
import fr.pivot.pilotage.schedule.service.SchedulingService;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing the constraint/deadline surface of {@link WbsTaskController} (US22.4.4 —
 * «&nbsp;Contraintes de date &amp; échéances&nbsp;»): read and set the single {@code task_constraint}
 * row (ASAP/ALAP/MSO/MFO/SNET/SNLT/FNET/FNLT, EN22.1a) of a task, plus its independent soft
 * {@code deadline}, always alongside the engine's current warnings about that task.
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> This service owns none of the constraint
 * scheduling maths: honouring a constraint, flooring/ceiling the CPM dates, dropping a constraint that
 * fights a hard dependency (and emitting {@code CONSTRAINT_CONFLICT}), and flagging a missed deadline
 * (emitting {@code DEADLINE_MISSED}) are all already implemented by the pure engine
 * ({@code fr.pivot.pilotage.schedule.engine.ScheduleEngine}, EN22.1b) and already wired end to end —
 * {@code SchedulingService.buildInput} already loads the persisted {@link TaskConstraint} of every
 * task into the engine's {@code TaskNode}. What US22.4.4 actually adds is the missing piece: a REST
 * surface to create/replace that row and to read the resulting warnings, plus the API-level validation
 * the engine itself does not own ({@code constraint_date} required for date-bearing types, US22.4.4
 * error AC).
 *
 * <p><strong>Read vs. write.</strong> {@link #get} calls
 * {@link SchedulingService#previewSchedule(long, long)} — a pure, non-persisting CPM run — so a plain
 * read never mutates the stored schedule and stays available to every role (Security AC: a conflict
 * raised by an editor remains visible read-only to a viewer, without requiring a fresh write). Only
 * {@link #upsert} persists, and after persisting it re-runs
 * {@link SchedulingService#scheduleProject(long, long)} exactly like every other US22.4.x mutation.
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and TODO-SETUP §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId} are
 * explicit arguments, never taken from a request body. Every operation resolves the target project and
 * task through a tenant+team-scoped lookup collapsing every isolation failure into one non-disclosing
 * {@link WbsProjectNotFoundException}/{@link WbsTaskNotFoundException} (404).
 */
@Service
public class TaskConstraintService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskConstraintService.class);

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskConstraintRepository constraintRepository;
    private final SchedulingService schedulingService;

    /**
     * Constructs the service.
     *
     * @param projectRepository    tenant/team-scoped project repository (isolation boundary)
     * @param taskRepository       tenant/team-scoped task repository (target resolution)
     * @param constraintRepository tenant/team-scoped constraint repository (EN22.1a)
     * @param schedulingService    the CPM driver that recomputes/previews and reports warnings (EN22.1b)
     */
    public TaskConstraintService(final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final TaskConstraintRepository constraintRepository, final SchedulingService schedulingService) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.constraintRepository = constraintRepository;
        this.schedulingService = schedulingService;
    }

    /**
     * Reads a task's current constraint/deadline and the engine's current warnings about it, without
     * persisting anything (US22.4.4 Security AC: available to every role, not only editors).
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the browsed project id
     * @param taskId    the task to read
     * @return the task's constraint/deadline (defaults to {@code ASAP}, no date, no deadline when no
     *         row was ever persisted) plus its current warnings
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if the task does not resolve on this project
     */
    @Transactional(readOnly = true)
    public TaskConstraintResponse get(final long tenantId, final long teamId, final long projectId,
            final long taskId) {
        requireProject(tenantId, teamId, projectId);
        requireTask(tenantId, teamId, projectId, taskId);
        final TaskConstraint constraint = constraintRepository
                .findByTaskIdAndTenantIdAndTeamId(taskId, tenantId, teamId).orElse(null);
        final ScheduleResult result = schedulingService.previewSchedule(projectId, tenantId);
        return toResponse(taskId, constraint, result);
    }

    /**
     * Creates or replaces a task's constraint/deadline and re-runs the CPM, returning the fresh state
     * and its warnings.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the browsed project id
     * @param taskId    the task to constrain
     * @param request   the constraint/deadline payload
     * @return the persisted constraint/deadline plus the engine's fresh warnings for this task
     * @throws WbsProjectNotFoundException   if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException      if the task does not resolve on this project
     * @throws InvalidTaskConstraintException if the type requires a date and none was supplied (422)
     */
    @Transactional
    public TaskConstraintResponse upsert(final long tenantId, final long teamId, final long projectId,
            final long taskId, final UpsertTaskConstraintRequest request) {
        requireProject(tenantId, teamId, projectId);
        requireTask(tenantId, teamId, projectId, taskId);

        final boolean dateless = request.constraintType() == ConstraintType.ASAP
                || request.constraintType() == ConstraintType.ALAP;
        if (!dateless && request.constraintDate() == null) {
            throw InvalidTaskConstraintException.missingConstraintDate(request.constraintType());
        }
        final Instant effectiveDate = dateless ? null : request.constraintDate();

        final TaskConstraint constraint = constraintRepository
                .findByTaskIdAndTenantIdAndTeamId(taskId, tenantId, teamId)
                .orElseGet(() -> new TaskConstraint(tenantId, teamId, taskId,
                        request.constraintType(), effectiveDate, request.deadline()));
        constraint.setConstraintType(request.constraintType());
        constraint.setConstraintDate(effectiveDate);
        constraint.setDeadline(request.deadline());
        constraintRepository.save(constraint);

        final ScheduleResult result = schedulingService.scheduleProject(projectId, tenantId);
        LOG.info("event=task_constraint_set tenant={} team={} project={} task={} type={} deadline={}",
                tenantId, teamId, projectId, taskId, request.constraintType(), request.deadline());
        return toResponse(taskId, constraint, result);
    }

    // ---- internals ------------------------------------------------------------------------------

    private TaskConstraintResponse toResponse(final long taskId, final TaskConstraint constraint,
            final ScheduleResult result) {
        final ConstraintType type = constraint != null ? constraint.getConstraintType() : ConstraintType.ASAP;
        final Instant constraintDate = constraint != null ? constraint.getConstraintDate() : null;
        final Instant deadline = constraint != null ? constraint.getDeadline() : null;
        return new TaskConstraintResponse(taskId, type, constraintDate, deadline, warningsFor(taskId, result));
    }

    private List<ConstraintWarningResponse> warningsFor(final long taskId, final ScheduleResult result) {
        return result.warnings().stream()
                .filter(w -> w.taskId() == taskId)
                .map(w -> new ConstraintWarningResponse(w.type(), w.detail()))
                .toList();
    }

    private void requireProject(final long tenantId, final long teamId, final long projectId) {
        projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsProjectNotFoundException(projectId, tenantId, teamId));
    }

    private Task requireTask(final long tenantId, final long teamId, final long projectId, final long taskId) {
        return taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(taskId, projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsTaskNotFoundException(taskId, projectId));
    }
}

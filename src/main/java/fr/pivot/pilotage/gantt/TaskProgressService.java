package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressHistory;
import fr.pivot.pilotage.schedule.TaskProgressHistoryRepository;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing the US22.4.8 progress endpoint of {@link WbsTaskController} — «&nbsp;suivi
 * d'avancement (% réalisé, réel/restant)&nbsp;». Upserts the task's single {@link TaskProgress} row
 * (EN22.1a, schema-level {@code UNIQUE task_id}), appends an immutable {@link TaskProgressHistory}
 * audit entry (security AC: "l'historique des saisies est tracé (auteur, date)") and re-derives
 * every assignment's actual/remaining work.
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> The charge-weighted rollup of a summary
 * task's percent complete (AC "given un récapitulatif, ... son % réalisé s'agrège") is already
 * produced by {@link fr.pivot.pilotage.schedule.projection.PlanProjectionService} (EN22.1c) and
 * surfaced by {@link WbsTaskService#tree}; this service owns only a leaf/milestone task's own
 * entry. Editing a summary task's aggregated percent directly is refused via the same
 * {@link DerivedFieldNotEditableException} already used for every other derived field on a summary
 * (US22.4.1c).
 *
 * <p><strong>Actual/remaining work (implementation note, MS-Project parity).</strong> The actual
 * work of each assignment is derived from the new percent complete
 * (<em>actual = round(percent% × work)</em>) and the remaining work then follows
 * <em>remaining = work − actual</em>, floored at {@code 0} so a rounding artefact can never go
 * negative. A task without an assignment yet has no total/actual/remaining work ({@code null} in
 * the response) — nothing to distribute the percent over.
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and TODO-SETUP §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId}
 * are explicit arguments, never taken from a request body, mirroring {@link TaskEffortService}.
 *
 * <p><strong>Author attribution (security AC, gap-era).</strong> {@code actorRef} is, like
 * {@code resourceRef} on an {@link Assignment} (ADR-006), a logical reference supplied by the
 * caller — {@code pivot-core-starter}'s authenticated principal is not yet consumable. It is never
 * used for authorization (that remains {@link WbsEditPolicy}'s job, gated at the controller before
 * this service is ever invoked) — only to stamp the audit trail's "auteur" column.
 */
@Service
public class TaskProgressService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskProgressService.class);

    /** Lower bound of a valid percent complete. */
    private static final BigDecimal PERCENT_MIN = BigDecimal.ZERO;

    /** Upper bound of a valid percent complete, and the percent basis for the actual-work relation. */
    private static final BigDecimal PERCENT_MAX = new BigDecimal("100");

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskProgressRepository progressRepository;
    private final TaskProgressHistoryRepository historyRepository;
    private final AssignmentRepository assignmentRepository;

    /**
     * Constructs the service.
     *
     * @param projectRepository  tenant/team-scoped project repository (isolation boundary)
     * @param taskRepository     tenant/team-scoped task repository (target resolution)
     * @param progressRepository task-progress repository (current-state row, EN22.1a)
     * @param historyRepository  task-progress-history repository (append-only audit trail, US22.4.8)
     * @param assignmentRepository assignment repository (actual/remaining work, EN22.1a)
     */
    public TaskProgressService(final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final TaskProgressRepository progressRepository, final TaskProgressHistoryRepository historyRepository,
            final AssignmentRepository assignmentRepository) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.progressRepository = progressRepository;
        this.historyRepository = historyRepository;
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Sets a task's progress: upserts its current-state row, appends an immutable audit entry and
     * re-derives its assignments' actual/remaining work.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to edit
     * @param request   the progress payload
     * @return the task's refreshed progress state
     * @throws WbsProjectNotFoundException      if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException          if the task does not resolve on this project
     * @throws DerivedFieldNotEditableException if the task is a summary (its percent is aggregated)
     * @throws InvalidTaskProgressException      if a percent is out of {@code [0, 100]}, or the
     *                                            actual finish precedes the actual start
     */
    @Transactional
    public TaskProgressResponse setProgress(final long tenantId, final long teamId, final long projectId,
            final long taskId, final UpdateTaskProgressRequest request) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);

        if (task.getNodeKind() == NodeKind.SUMMARY) {
            throw DerivedFieldNotEditableException.summaryField(taskId, "percentComplete");
        }
        validate(request);

        final TaskProgress progress = progressRepository.findByTaskIdAndTenantIdAndTeamId(taskId, tenantId, teamId)
                .orElseGet(() -> new TaskProgress(tenantId, teamId, taskId, request.percentComplete()));
        progress.setPercentComplete(request.percentComplete());
        progress.setPhysicalPercentComplete(request.physicalPercentComplete());
        progress.setActualStart(request.actualStart());
        progress.setActualFinish(request.actualFinish());
        progress.setStatusDate(request.statusDate());
        progressRepository.save(progress);

        historyRepository.save(new TaskProgressHistory(tenantId, teamId, taskId, request.actorRef(),
                request.percentComplete(), request.physicalPercentComplete(), request.actualStart(),
                request.actualFinish(), request.statusDate()));

        final WorkTotals totals = reprojectWork(tenantId, teamId, taskId, request.percentComplete());

        task.setRevision((task.getRevision() == null ? 0 : task.getRevision()) + 1);
        taskRepository.save(task);

        LOG.info("event=task_progress_set tenant={} team={} project={} task={} actor={} percentComplete={}",
                tenantId, teamId, projectId, taskId, request.actorRef(), request.percentComplete());

        final String label = request.percentComplete().stripTrailingZeros().toPlainString() + "%";
        return new TaskProgressResponse(taskId, progress.getPercentComplete(), label,
                progress.getPhysicalPercentComplete(), totals.actual(), totals.remaining(), totals.total(),
                progress.getActualStart(), progress.getActualFinish(), progress.getStatusDate(),
                task.getRevision());
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * Guards the error ACs (US22.4.8): a percent outside {@code [0, 100]}, or an actual finish
     * preceding the actual start.
     */
    private void validate(final UpdateTaskProgressRequest request) {
        if (isOutOfRange(request.percentComplete())) {
            throw InvalidTaskProgressException.percentOutOfRange(request.percentComplete());
        }
        if (request.physicalPercentComplete() != null && isOutOfRange(request.physicalPercentComplete())) {
            throw InvalidTaskProgressException.percentOutOfRange(request.physicalPercentComplete());
        }
        if (request.actualStart() != null && request.actualFinish() != null
                && request.actualFinish().isBefore(request.actualStart())) {
            throw InvalidTaskProgressException.actualFinishBeforeActualStart(
                    request.actualStart(), request.actualFinish());
        }
    }

    private static boolean isOutOfRange(final BigDecimal percent) {
        return percent.compareTo(PERCENT_MIN) < 0 || percent.compareTo(PERCENT_MAX) > 0;
    }

    /**
     * Re-derives every assignment's actual/remaining work from the new percent complete
     * (<em>actual = round(percent% × work)</em>, <em>remaining = work − actual</em>, floored at
     * {@code 0}), and returns the task's total/actual/remaining work summed over its assignments.
     */
    private WorkTotals reprojectWork(final long tenantId, final long teamId, final long taskId,
            final BigDecimal percentComplete) {
        final List<Assignment> assignments =
                assignmentRepository.findAllByTaskIdAndTenantIdAndTeamId(taskId, tenantId, teamId);
        if (assignments.isEmpty()) {
            return new WorkTotals(null, null, null);
        }
        int total = 0;
        int actual = 0;
        int remaining = 0;
        for (final Assignment a : assignments) {
            final int work = a.getWorkMinutes() != null ? a.getWorkMinutes() : 0;
            final int actualWork = percentComplete.multiply(BigDecimal.valueOf(work))
                    .divide(PERCENT_MAX, 0, RoundingMode.HALF_UP)
                    .intValue();
            final int remainingWork = Math.max(work - actualWork, 0);
            a.setActualWorkMinutes(actualWork);
            a.setRemainingWorkMinutes(remainingWork);
            total += work;
            actual += actualWork;
            remaining += remainingWork;
        }
        assignmentRepository.saveAll(assignments);
        return new WorkTotals(total, actual, remaining);
    }

    /** Task-level totals of an assignment field, summed over every assignment of a task. */
    private record WorkTotals(Integer total, Integer actual, Integer remaining) {
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

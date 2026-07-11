package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.engine.ScheduleErrorCode;
import fr.pivot.pilotage.schedule.engine.ScheduleException;
import fr.pivot.pilotage.schedule.service.SchedulingService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing the typed-dependency management surface of {@link WbsTaskController}
 * (US22.4.3 — «&nbsp;Dépendances typées FS/SS/FF/SF + retard/avance&nbsp;»): create, retype, list and
 * delete a link between two tasks of the same project, each mutation re-running the CPM and rejecting
 * a cycle atomically.
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> This service owns none of the scheduling
 * maths. The four link types, the signed lag/lead, and the cycle rejection are all owned by the pure
 * engine ({@code fr.pivot.pilotage.schedule.engine.ScheduleEngine}) and driven by
 * {@link SchedulingService}. On every write this service persists the {@link TaskDependency} row and
 * then invokes {@link SchedulingService#scheduleProject(long, long)} <em>within the same
 * transaction</em>; when the reloaded graph is cyclic the engine raises
 * {@link ScheduleErrorCode#SCHEDULE_CYCLE} and the whole transaction rolls back — the tentative row
 * is never committed (atomicity AC), and the caller receives a {@link DependencyCycleException}.
 *
 * <p><strong>Lag semantics (decision D7, Gate 1 clarification).</strong> The persisted
 * {@code lag_minutes} is interpreted by the engine in <strong>worked minutes</strong> on the
 * successor task's effective calendar ({@code WorkingCalendar.advance}/{@code retreat}), i.e.
 * <em>ouvré</em>, not calendar time. This repo therefore models a single lag mode (worked) rather
 * than letting calendar-time and worked-time lags coexist — the engine has no calendar-time lag path,
 * so exposing one here would be inconsistent with the projection. If a future US needs a
 * calendar-time lag it must first be added to the engine.
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and TODO-SETUP §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId} are
 * explicit arguments, never taken from a request body. Every project/task/dependency is resolved
 * through a tenant+team-scoped lookup collapsing every isolation failure into one non-disclosing
 * {@link WbsProjectNotFoundException}/{@link DependencyNotFoundException} (404).
 */
@Service
public class DependencyService {

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final SchedulingService schedulingService;

    /**
     * Constructs the service.
     *
     * @param projectRepository    tenant/team-scoped project repository (isolation boundary)
     * @param taskRepository       tenant/team-scoped task repository (endpoint resolution)
     * @param dependencyRepository tenant/team-scoped dependency repository (EN22.1a)
     * @param schedulingService    the CPM driver that recomputes and rejects cycles (EN22.1b)
     */
    public DependencyService(final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final TaskDependencyRepository dependencyRepository, final SchedulingService schedulingService) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.dependencyRepository = dependencyRepository;
        this.schedulingService = schedulingService;
    }

    /**
     * Lists every dependency incident to the project's tasks, ordered by id.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the browsed project id
     * @return the dependencies of the project (possibly empty)
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public List<DependencyResponse> list(final long tenantId, final long teamId, final long projectId) {
        requireProject(tenantId, teamId, projectId);
        final Map<Long, TaskDependency> byId = new LinkedHashMap<>();
        for (final Task t : taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)) {
            for (final TaskDependency d : dependencyRepository
                    .findAllByPredecessorTaskIdAndTenantIdAndTeamId(t.getId(), tenantId, teamId)) {
                byId.put(d.getId(), d);
            }
        }
        return byId.values().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(DependencyService::toResponse)
                .toList();
    }

    /**
     * Creates a typed dependency between two tasks of the project and re-runs the CPM; a cycle is
     * rejected atomically (nothing persisted).
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the browsed project id
     * @param request   the creation payload (link type defaults to FS, lag defaults to 0)
     * @return the created dependency
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws DependencyNotFoundException if either endpoint task is not visible on the project
     * @throws InvalidDependencyException  if the link targets the task itself (422)
     * @throws DuplicateDependencyException if the same (pred, succ, type) already exists (409)
     * @throws DependencyCycleException    if the link introduces a cycle (409, {@code SCHEDULE_CYCLE})
     */
    @Transactional
    public DependencyResponse create(final long tenantId, final long teamId, final long projectId,
            final CreateDependencyRequest request) {
        requireProject(tenantId, teamId, projectId);
        if (request.predecessorTaskId().equals(request.successorTaskId())) {
            throw InvalidDependencyException.selfDependency(request.predecessorTaskId());
        }
        requireTaskOnProject(request.predecessorTaskId(), projectId, tenantId, teamId);
        requireTaskOnProject(request.successorTaskId(), projectId, tenantId, teamId);
        dependencyRepository
                .findByPredecessorTaskIdAndSuccessorTaskIdAndLinkTypeAndTenantIdAndTeamId(
                        request.predecessorTaskId(), request.successorTaskId(), request.linkType(), tenantId, teamId)
                .ifPresent(existing -> {
                    throw new DuplicateDependencyException(request.predecessorTaskId(),
                            request.successorTaskId(), request.linkType());
                });

        final TaskDependency saved = dependencyRepository.save(new TaskDependency(tenantId, teamId,
                request.predecessorTaskId(), request.successorTaskId(), request.linkType(), request.lagMinutes()));
        recomputeOrRejectCycle(projectId, tenantId);
        return toResponse(saved);
    }

    /**
     * Retypes an existing dependency and/or changes its lag/lead, then re-runs the CPM; a cycle is
     * rejected atomically (the edit is rolled back).
     *
     * @param tenantId     the requesting tenant's {@code public.tenants.id}
     * @param teamId       the requesting team's {@code public.teams.id}
     * @param projectId    the browsed project id
     * @param dependencyId the dependency id
     * @param request      the update payload (link type required, lag defaults to 0)
     * @return the updated dependency
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws DependencyNotFoundException if the dependency is not visible, or its endpoints are not
     *                                     on the project
     * @throws DuplicateDependencyException if the retype collides with an existing link (409)
     * @throws DependencyCycleException    if the change introduces a cycle (409, {@code SCHEDULE_CYCLE})
     */
    @Transactional
    public DependencyResponse update(final long tenantId, final long teamId, final long projectId,
            final long dependencyId, final UpdateDependencyRequest request) {
        requireProject(tenantId, teamId, projectId);
        final TaskDependency dependency = requireDependencyOnProject(dependencyId, projectId, tenantId, teamId);

        if (request.linkType() != dependency.getLinkType()) {
            dependencyRepository
                    .findByPredecessorTaskIdAndSuccessorTaskIdAndLinkTypeAndTenantIdAndTeamId(
                            dependency.getPredecessorTaskId(), dependency.getSuccessorTaskId(),
                            request.linkType(), tenantId, teamId)
                    .ifPresent(existing -> {
                        throw new DuplicateDependencyException(dependency.getPredecessorTaskId(),
                                dependency.getSuccessorTaskId(), request.linkType());
                    });
        }
        dependency.setLinkType(request.linkType());
        dependency.setLagMinutes(request.lagMinutes());
        final TaskDependency saved = dependencyRepository.save(dependency);
        recomputeOrRejectCycle(projectId, tenantId);
        return toResponse(saved);
    }

    /**
     * Deletes a dependency and re-runs the CPM (removing an edge can never create a cycle).
     *
     * @param tenantId     the requesting tenant's {@code public.tenants.id}
     * @param teamId       the requesting team's {@code public.teams.id}
     * @param projectId    the browsed project id
     * @param dependencyId the dependency id
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws DependencyNotFoundException if the dependency is not visible on the project
     */
    @Transactional
    public void delete(final long tenantId, final long teamId, final long projectId, final long dependencyId) {
        requireProject(tenantId, teamId, projectId);
        final TaskDependency dependency = requireDependencyOnProject(dependencyId, projectId, tenantId, teamId);
        dependencyRepository.delete(dependency);
        recomputeOrRejectCycle(projectId, tenantId);
    }

    // ---- shared guards --------------------------------------------------------------------------

    private void requireProject(final long tenantId, final long teamId, final long projectId) {
        projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsProjectNotFoundException(projectId, tenantId, teamId));
    }

    private void requireTaskOnProject(final long taskId, final long projectId, final long tenantId,
            final long teamId) {
        taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(taskId, projectId, tenantId, teamId)
                .orElseThrow(() -> DependencyNotFoundException.endpointTask(taskId, projectId));
    }

    private TaskDependency requireDependencyOnProject(final long dependencyId, final long projectId,
            final long tenantId, final long teamId) {
        final TaskDependency dependency = dependencyRepository
                .findByIdAndTenantIdAndTeamId(dependencyId, tenantId, teamId)
                .orElseThrow(() -> new DependencyNotFoundException(dependencyId, tenantId, teamId));
        // Endpoint tasks must resolve on the browsed project — otherwise the dependency is not this
        // project's business and must not be disclosed.
        requireTaskOnProject(dependency.getPredecessorTaskId(), projectId, tenantId, teamId);
        requireTaskOnProject(dependency.getSuccessorTaskId(), projectId, tenantId, teamId);
        return dependency;
    }

    /**
     * Re-runs the CPM in the current transaction; a {@link ScheduleErrorCode#SCHEDULE_CYCLE} raised by
     * the engine is translated into a {@link DependencyCycleException} — because it propagates out of
     * this {@code @Transactional} method the whole change (the tentative persist/edit/delete) is
     * rolled back, so no partial state ever survives (atomicity AC).
     */
    private void recomputeOrRejectCycle(final long projectId, final long tenantId) {
        try {
            schedulingService.scheduleProject(projectId, tenantId);
        } catch (final ScheduleException ex) {
            if (ex.code() == ScheduleErrorCode.SCHEDULE_CYCLE) {
                throw new DependencyCycleException(ex.getMessage());
            }
            throw ex;
        }
    }

    private static DependencyResponse toResponse(final TaskDependency d) {
        final int lag = d.getLagMinutes() != null ? d.getLagMinutes() : 0;
        return new DependencyResponse(d.getId(), d.getPredecessorTaskId(), d.getSuccessorTaskId(),
                d.getLinkType(), lag);
    }
}

package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Task} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant- and project-scoped lookups; every read path used by future
 * endpoints must go through a tenant-scoped variant to preserve multi-tenant isolation.
 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * Finds all tasks belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the tasks owned by that tenant (possibly empty)
     */
    List<Task> findAllByTenantId(Long tenantId);

    /**
     * Finds all tasks belonging to the given tenant and team (per-team portfolio scoping, team_id
     * retrofit).
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the tasks owned by that tenant/team (possibly empty)
     */
    List<Task> findAllByTenantIdAndTeamId(Long tenantId, Long teamId);

    /**
     * Finds all tasks of the given project within the given tenant.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @return the tasks (possibly empty)
     */
    List<Task> findAllByProjectIdAndTenantId(Long projectId, Long tenantId);

    /**
     * Finds all tasks of the given project within the given tenant and team (per-team portfolio
     * scoping, team_id retrofit).
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @return the tasks (possibly empty)
     */
    List<Task> findAllByProjectIdAndTenantIdAndTeamId(Long projectId, Long tenantId, Long teamId);

    /**
     * Finds a task by id, verifying it belongs to the given tenant.
     *
     * @param id       the task id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} with the task, or empty if not found for that tenant
     */
    Optional<Task> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Finds a task by id, verifying it belongs to the given tenant and team (per-team portfolio
     * scoping, team_id retrofit).
     *
     * @param id       the task id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @param teamId   the expected team's {@code public.teams.id}
     * @return an {@link Optional} with the task, or empty if not found for that tenant/team
     */
    Optional<Task> findByIdAndTenantIdAndTeamId(Long id, Long tenantId, Long teamId);

    /**
     * Finds a task by id, verifying it belongs to the given project, tenant and team (US22.3.1
     * roadmap-rapide initiative lookup — the initiative must resolve within the exact project the
     * caller is browsing, not merely somewhere in the tenant/team).
     *
     * @param id        the task id
     * @param projectId the expected owning {@code pilotage.project.id}
     * @param tenantId  the expected tenant's {@code public.tenants.id}
     * @param teamId    the expected team's {@code public.teams.id}
     * @return an {@link Optional} with the task, or empty if not found for that project/tenant/team
     */
    Optional<Task> findByIdAndProjectIdAndTenantIdAndTeamId(Long id, Long projectId, Long tenantId, Long teamId);

    /**
     * Finds all roadmap-rapide initiatives (tasks assigned to a lane, US22.3.1) of the given
     * project within the given tenant and team.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @return the lane-assigned tasks (possibly empty)
     */
    List<Task> findAllByProjectIdAndTenantIdAndTeamIdAndLaneIdIsNotNull(Long projectId, Long tenantId, Long teamId);

    /**
     * Counts the roadmap-rapide initiatives already posed on the given lane (US22.3.1) — used to
     * append a newly created initiative at the end of its lane's display order.
     *
     * @param laneId   the {@code pilotage.lane.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the number of initiatives currently on that lane
     */
    long countByLaneIdAndTenantIdAndTeamId(Long laneId, Long tenantId, Long teamId);

    /**
     * Finds all tasks of the given kind (e.g. strategic milestones, US22.3.4) belonging to the
     * given project within the given tenant and team.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @param nodeKind  the node kind to filter on
     * @return the matching tasks (possibly empty)
     */
    List<Task> findAllByProjectIdAndTenantIdAndTeamIdAndNodeKind(Long projectId, Long tenantId, Long teamId,
            NodeKind nodeKind);

    /**
     * Counts the tasks of the given kind (e.g. strategic milestones, US22.3.4) already created on
     * the given project — used to append a newly created one at the end of its own display order.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @param nodeKind  the node kind to filter on
     * @return the number of matching tasks currently on that project
     */
    long countByProjectIdAndTenantIdAndTeamIdAndNodeKind(Long projectId, Long tenantId, Long teamId,
            NodeKind nodeKind);
}

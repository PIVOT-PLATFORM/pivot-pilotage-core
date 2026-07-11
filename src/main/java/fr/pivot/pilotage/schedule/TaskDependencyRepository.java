package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TaskDependency} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant-scoped lookups to preserve multi-tenant isolation.
 */
public interface TaskDependencyRepository extends JpaRepository<TaskDependency, Long> {

    /**
     * Finds all dependencies belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the dependencies owned by that tenant (possibly empty)
     */
    List<TaskDependency> findAllByTenantId(Long tenantId);

    /**
     * Finds all dependencies belonging to the given tenant and team (per-team portfolio scoping,
     * team_id retrofit).
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the dependencies owned by that tenant/team (possibly empty)
     */
    List<TaskDependency> findAllByTenantIdAndTeamId(Long tenantId, Long teamId);

    /**
     * Finds all dependencies whose predecessor is the given task, within the given tenant.
     *
     * @param predecessorTaskId the predecessor {@code pilotage.task.id}
     * @param tenantId          the {@code public.tenants.id} to restrict results to
     * @return the dependencies (possibly empty)
     */
    List<TaskDependency> findAllByPredecessorTaskIdAndTenantId(Long predecessorTaskId, Long tenantId);

    /**
     * Finds all dependencies whose predecessor is the given task, within the given tenant and team
     * (per-team portfolio scoping, team_id retrofit).
     *
     * @param predecessorTaskId the predecessor {@code pilotage.task.id}
     * @param tenantId          the {@code public.tenants.id} to restrict results to
     * @param teamId            the {@code public.teams.id} to restrict results to
     * @return the dependencies (possibly empty)
     */
    List<TaskDependency> findAllByPredecessorTaskIdAndTenantIdAndTeamId(
            Long predecessorTaskId, Long tenantId, Long teamId);

    /**
     * Finds a dependency by id, verifying it belongs to the given tenant and team (per-team portfolio
     * scoping) — the non-disclosing lookup a management endpoint uses to resolve a single edge.
     *
     * @param id       the dependency id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @param teamId   the expected team's {@code public.teams.id}
     * @return an {@link Optional} with the dependency, or empty if not found for that tenant/team
     */
    Optional<TaskDependency> findByIdAndTenantIdAndTeamId(Long id, Long tenantId, Long teamId);

    /**
     * Finds every dependency incident to the given task (as predecessor or successor) within the
     * given tenant and team — used to list a task's links and to cascade a deletion when one of the
     * two endpoint tasks disappears.
     *
     * @param predecessorTaskId the predecessor {@code pilotage.task.id} to match
     * @param successorTaskId   the successor {@code pilotage.task.id} to match
     * @param tenantId          the {@code public.tenants.id} to restrict results to
     * @param teamId            the {@code public.teams.id} to restrict results to
     * @return the incident dependencies (possibly empty)
     */
    List<TaskDependency> findAllByPredecessorTaskIdOrSuccessorTaskIdAndTenantIdAndTeamId(
            Long predecessorTaskId, Long successorTaskId, Long tenantId, Long teamId);

    /**
     * Detects an already-persisted duplicate of a would-be dependency (same predecessor, successor
     * and link type) within the given tenant and team — enforces the schema
     * {@code UNIQUE(predecessor, successor, link_type)} with an explicit caller-facing error before
     * the constraint fires.
     *
     * @param predecessorTaskId the predecessor {@code pilotage.task.id}
     * @param successorTaskId   the successor {@code pilotage.task.id}
     * @param linkType          the link type
     * @param tenantId          the {@code public.tenants.id} to restrict results to
     * @param teamId            the {@code public.teams.id} to restrict results to
     * @return an {@link Optional} with the existing duplicate, or empty if none
     */
    Optional<TaskDependency> findByPredecessorTaskIdAndSuccessorTaskIdAndLinkTypeAndTenantIdAndTeamId(
            Long predecessorTaskId, Long successorTaskId, DependencyLinkType linkType, Long tenantId, Long teamId);
}

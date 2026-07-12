package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Assignment} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant- and task-scoped lookups to preserve multi-tenant isolation.
 */
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /**
     * Finds all assignments belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the assignments owned by that tenant (possibly empty)
     */
    List<Assignment> findAllByTenantId(Long tenantId);

    /**
     * Finds all assignments belonging to the given tenant and team (per-team portfolio scoping,
     * team_id retrofit).
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the assignments owned by that tenant/team (possibly empty)
     */
    List<Assignment> findAllByTenantIdAndTeamId(Long tenantId, Long teamId);

    /**
     * Finds all assignments of the given task within the given tenant.
     *
     * @param taskId   the {@code pilotage.task.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the assignments (possibly empty)
     */
    List<Assignment> findAllByTaskIdAndTenantId(Long taskId, Long tenantId);

    /**
     * Finds all assignments of the given task within the given tenant and team (per-team
     * portfolio scoping, team_id retrofit).
     *
     * @param taskId   the {@code pilotage.task.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the assignments (possibly empty)
     */
    List<Assignment> findAllByTaskIdAndTenantIdAndTeamId(Long taskId, Long tenantId, Long teamId);

    /**
     * Finds all assignments of the given tasks within the given tenant and team, in one batch query
     * (US22.4.9 baseline capture/variance) — avoids one query per task when a project's baseline is
     * posed or its variance computed, keeping the operation a lightweight batch read rather than an
     * N+1 walk even on a 10 000+ task plan (EN22.2 perf note).
     *
     * @param taskIds  the {@code pilotage.task.id} values to fetch assignments for
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the assignments (possibly empty)
     */
    List<Assignment> findAllByTaskIdInAndTenantIdAndTeamId(Collection<Long> taskIds, Long tenantId, Long teamId);
}

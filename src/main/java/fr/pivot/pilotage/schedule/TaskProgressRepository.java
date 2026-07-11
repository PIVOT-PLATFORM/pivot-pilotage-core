package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TaskProgress} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant-scoped lookups. A task carries exactly one progress record
 * (schema-level {@code UNIQUE task_id}).
 */
public interface TaskProgressRepository extends JpaRepository<TaskProgress, Long> {

    /**
     * Finds the (at most one) progress record of the given task within the given tenant.
     *
     * @param taskId   the {@code pilotage.task.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return an {@link Optional} with the progress, or empty if none
     */
    Optional<TaskProgress> findByTaskIdAndTenantId(Long taskId, Long tenantId);

    /**
     * Finds the (at most one) progress record of the given task within the given tenant and team
     * (per-team portfolio scoping, team_id retrofit).
     *
     * @param taskId   the {@code pilotage.task.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return an {@link Optional} with the progress, or empty if none
     */
    Optional<TaskProgress> findByTaskIdAndTenantIdAndTeamId(Long taskId, Long tenantId, Long teamId);
}

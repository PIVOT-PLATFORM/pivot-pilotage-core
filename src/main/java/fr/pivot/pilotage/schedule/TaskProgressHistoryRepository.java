package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link TaskProgressHistory} entities (US22.4.8) — append-only
 * audit trail of every progress save (security AC: "l'historique des saisies est tracé (auteur,
 * date)").
 */
public interface TaskProgressHistoryRepository extends JpaRepository<TaskProgressHistory, Long> {

    /**
     * Finds every history entry of a task within a tenant/team, most recent first.
     *
     * @param taskId   the {@code pilotage.task.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param teamId   the {@code public.teams.id} to restrict results to
     * @return the entries ordered by {@code recordedAt} descending (possibly empty)
     */
    List<TaskProgressHistory> findAllByTaskIdAndTenantIdAndTeamIdOrderByRecordedAtDesc(
            Long taskId, Long tenantId, Long teamId);
}

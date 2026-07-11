package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link TaskConstraint} entities (EN22.1a).
 *
 * <p>Standard CRUD plus tenant-scoped lookups. A task carries at most one constraint (schema-level
 * {@code UNIQUE task_id}).
 */
public interface TaskConstraintRepository extends JpaRepository<TaskConstraint, Long> {

    /**
     * Finds the (at most one) constraint of the given task within the given tenant.
     *
     * @param taskId   the constrained {@code pilotage.task.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return an {@link Optional} with the constraint, or empty if none
     */
    Optional<TaskConstraint> findByTaskIdAndTenantId(Long taskId, Long tenantId);
}

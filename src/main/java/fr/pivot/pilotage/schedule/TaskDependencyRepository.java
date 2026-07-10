package fr.pivot.pilotage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
     * Finds all dependencies whose predecessor is the given task, within the given tenant.
     *
     * @param predecessorTaskId the predecessor {@code pilotage.task.id}
     * @param tenantId          the {@code public.tenants.id} to restrict results to
     * @return the dependencies (possibly empty)
     */
    List<TaskDependency> findAllByPredecessorTaskIdAndTenantId(Long predecessorTaskId, Long tenantId);
}

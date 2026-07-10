package fr.pivot.pilotage.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Project} entities (EN18.1).
 *
 * <p>Provides standard CRUD operations through {@link JpaRepository} plus tenant-scoped and
 * application-scoped lookups. All read paths used by future endpoints must go through a
 * tenant-scoped variant to preserve multi-tenant isolation.
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Finds all projects belonging to the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @return the projects owned by that tenant (possibly empty)
     */
    List<Project> findAllByTenantId(Long tenantId);

    /**
     * Finds all projects attached to the given application.
     *
     * @param applicationId the parent {@code pilotage.application.id}
     * @return the projects of that application (possibly empty)
     */
    List<Project> findAllByApplicationId(Long applicationId);

    /**
     * Finds a project by its identifier, verifying it belongs to the given tenant.
     *
     * <p>Returns {@link Optional#empty()} if the project does not exist or belongs to a
     * different tenant, preventing cross-tenant information disclosure.
     *
     * @param id       the project id
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the project, or empty if not found
     */
    Optional<Project> findByIdAndTenantId(Long id, Long tenantId);
}

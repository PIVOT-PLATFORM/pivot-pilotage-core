package fr.pivot.pilotage.consolidation;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped resolver of the {@code project → application} chain (EN18.9). It attests that any
 * project-level datum (which always carries a {@code project_id}) traces back to <strong>exactly
 * one</strong> application — the deterministic parent guaranteed by the mandatory,
 * one-application-per-project FK created in EN18.1 ({@code pilotage.project.application_id NOT
 * NULL}).
 *
 * <p>This adds <em>no</em> new constraint: the invariant lives in the schema (EN18.1). Here it is
 * only <em>exposed</em> as a read query so callers (and {@link ApplicationConsolidationService})
 * can resolve the parent application of a project without duplicating the join logic — and always
 * within the tenant boundary.
 *
 * <p><strong>REST deferred.</strong> Per CLAUDE.md §gap and TODO-SETUP §5, {@code
 * pivot-core-starter} (TenantContext) is not published, so {@code tenantId} is an explicit argument,
 * never taken from a body/param/header.
 */
@Service
public class ProjectApplicationResolver {

    private final ProjectRepository projectRepository;

    /**
     * Constructs the resolver.
     *
     * @param projectRepository the tenant-scoped project repository
     */
    public ProjectApplicationResolver(final ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /**
     * Resolves the id of the single application a project belongs to, within the tenant boundary.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param projectId the project id whose parent application is sought
     * @return the parent {@code pilotage.application.id} — always exactly one
     * @throws ProjectNotFoundException if the project does not exist or is not visible to the tenant
     *                                  (cross-tenant access is treated as absent — 404 equivalent)
     */
    @Transactional(readOnly = true)
    public long resolveApplicationId(final long tenantId, final long projectId) {
        final Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, tenantId));
        return project.getApplication().getId();
    }
}

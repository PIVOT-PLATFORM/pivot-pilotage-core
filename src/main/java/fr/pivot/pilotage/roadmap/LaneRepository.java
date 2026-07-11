package fr.pivot.pilotage.roadmap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Lane} entities (US22.3.1).
 *
 * <p>Every read/write path used by {@code RoadmapService} goes through a tenant/team/project
 * scoped variant to preserve multi-tenant isolation (CLAUDE.md §Isolation tenant).
 */
public interface LaneRepository extends JpaRepository<Lane, Long> {

    /**
     * Finds all lanes of the given project within the given tenant and team, ordered by display
     * position (then id, for a stable tie-break).
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @return the lanes (possibly empty), ordered by position
     */
    List<Lane> findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(
            Long projectId, Long tenantId, Long teamId);

    /**
     * Finds a lane by id, verifying it belongs to the given project, tenant and team.
     *
     * @param id        the lane id
     * @param projectId the expected owning {@code pilotage.project.id}
     * @param tenantId  the expected tenant's {@code public.tenants.id}
     * @param teamId    the expected team's {@code public.teams.id}
     * @return an {@link Optional} with the lane, or empty if not found for that project/tenant/team
     */
    Optional<Lane> findByIdAndProjectIdAndTenantIdAndTeamId(Long id, Long projectId, Long tenantId, Long teamId);

    /**
     * Checks whether a lane with the given label (case-insensitive) already exists for the given
     * project — backs the duplicate-name conflict check on creation.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @param name      the candidate label
     * @return {@code true} if a lane with that label (any case) already exists for the project
     */
    boolean existsByProjectIdAndTenantIdAndTeamIdAndNameIgnoreCase(
            Long projectId, Long tenantId, Long teamId, String name);

    /**
     * Counts the lanes already created for the given project — used to append a newly created
     * lane at the end of the project's display order.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @return the number of lanes currently on that project
     */
    long countByProjectIdAndTenantIdAndTeamId(Long projectId, Long tenantId, Long teamId);
}

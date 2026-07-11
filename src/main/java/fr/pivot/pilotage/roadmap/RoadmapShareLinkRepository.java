package fr.pivot.pilotage.roadmap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RoadmapShareLink} entities (US22.3.5).
 *
 * <p>Two distinct access shapes, mirroring the two capabilities of this US:
 * <ul>
 *   <li>{@link #findByTokenHash(String)} — the <strong>public</strong> read path, used by
 *       {@code RoadmapShareService.viewSharedRoadmap(String)}. Deliberately <em>not</em>
 *       tenant/team/project-scoped: the caller starts with only an opaque token, before any of
 *       those identifiers are known — the row itself carries them once resolved.</li>
 *   <li>The tenant/team/project-scoped finders — the <strong>authenticated management</strong>
 *       path (list, revoke), gated by {@link RoadmapEditPolicy} at the controller.</li>
 * </ul>
 */
public interface RoadmapShareLinkRepository extends JpaRepository<RoadmapShareLink, Long> {

    /**
     * Finds a share link by its token hash — the only lookup available to the public,
     * unauthenticated read endpoint.
     *
     * @param tokenHash SHA-256 hex-encoded hash of the raw bearer token
     * @return the matching link, or empty if no link was ever created with that token
     */
    Optional<RoadmapShareLink> findByTokenHash(String tokenHash);

    /**
     * Lists every share link ever created for a project, most recent first — backs the
     * authenticated management listing endpoint.
     *
     * @param projectId the parent {@code pilotage.project.id}
     * @param tenantId  the {@code public.tenants.id} to restrict results to
     * @param teamId    the {@code public.teams.id} to restrict results to
     * @return the links (possibly empty), ordered by creation date descending
     */
    List<RoadmapShareLink> findAllByProjectIdAndTenantIdAndTeamIdOrderByCreatedAtDesc(
            Long projectId, Long tenantId, Long teamId);

    /**
     * Finds a share link by id, verifying it belongs to the given project, tenant and team —
     * used to authorize revocation of a specific link (prevents an authorized user of one project
     * from revoking another project's link by guessing an id).
     *
     * @param id        the share link id
     * @param projectId the expected owning {@code pilotage.project.id}
     * @param tenantId  the expected tenant's {@code public.tenants.id}
     * @param teamId    the expected team's {@code public.teams.id}
     * @return an {@link Optional} with the link, or empty if not found for that project/tenant/team
     */
    Optional<RoadmapShareLink> findByIdAndProjectIdAndTenantIdAndTeamId(
            Long id, Long projectId, Long tenantId, Long teamId);
}

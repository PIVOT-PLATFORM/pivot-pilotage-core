package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing US22.3.5 ("Partage & export de la roadmap") — the read-only share-link
 * mechanism. Export itself (PNG/PDF) has <strong>no backend role</strong>: the PO Agent +
 * Architect decision recorded here (and in the PR/pivot-docs spec) is that fidelity to the live
 * rendered roadmap is best achieved by a <strong>client-side capture</strong> in
 * {@code pivot-pilotage-ui} of the same DOM/canvas already displayed — duplicating the roadmap
 * layout/rendering logic server-side would be costly and risks silently diverging from what the
 * user actually sees, for a "Should"-priority, "Size: S" story. This service's only contribution
 * to the export AC is therefore ensuring the data an exporter needs — including a share-link
 * recipient with no session — is correctly and completely readable.
 *
 * <p>Owns three operations:
 * <ul>
 *   <li>{@link #createShareLink}/{@link #listShareLinks}/{@link #revokeShareLink} — authenticated
 *       management, gated by {@link RoadmapEditPolicy} at {@link RoadmapShareLinkController}
 *       (same population as "who may edit this roadmap", per the security AC).</li>
 *   <li>{@link #viewSharedRoadmap} — the public, unauthenticated read path consumed by
 *       {@link RoadmapShareViewController}, resolving a raw token to its project's lanes and
 *       initiatives via {@link RoadmapService} (reused as-is, not duplicated).</li>
 * </ul>
 *
 * <p><strong>Token discipline</strong> mirrors {@code pivot-core}'s
 * {@code fr.pivot.auth.entity.AccessToken}/{@code fr.pivot.auth.util.CryptoUtils}: a 256-bit
 * {@link SecureRandom} value, hex-encoded (64 chars), is generated on creation and returned
 * exactly once ({@link CreateShareLinkResponse#token()}); only its SHA-256 hex hash is ever
 * persisted ({@link RoadmapShareLink#getTokenHash()}). Not implemented as a dependency on
 * {@code pivot-core-starter}'s equivalent utility — that starter does not export one, and even if
 * it did, this gap-era module has no dependency on it yet (CLAUDE.md §gap, {@code TODO-SETUP.md}
 * §5); the algorithm is replicated locally instead.
 */
@Service
public class RoadmapShareService {

    /** Raw token length in bytes — 256 bits, matching {@code AccessToken}'s raw token format. */
    private static final int RAW_TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ProjectRepository projectRepository;
    private final RoadmapShareLinkRepository shareLinkRepository;
    private final RoadmapService roadmapService;

    /**
     * Constructs the service.
     *
     * @param projectRepository   tenant/team-scoped project repository (EN18.1)
     * @param shareLinkRepository share-link repository (US22.3.5)
     * @param roadmapService      the existing roadmap-rapide read logic (US22.3.1), reused
     *                            verbatim for the public view rather than duplicated
     */
    public RoadmapShareService(final ProjectRepository projectRepository,
            final RoadmapShareLinkRepository shareLinkRepository, final RoadmapService roadmapService) {
        this.projectRepository = projectRepository;
        this.shareLinkRepository = shareLinkRepository;
        this.roadmapService = roadmapService;
    }

    // ---- authenticated management (gated by RoadmapEditPolicy at the controller) ----------------

    /**
     * Creates a new read-only share link for a project.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id to share
     * @param request   the creation payload (optional expiry)
     * @return the created link, including the raw token (returned this one time only)
     * @throws ProjectNotFoundException          if the project is not visible to the tenant/team
     * @throws InvalidShareLinkExpiryException if {@code expiresAt} is not strictly in the future
     */
    @Transactional
    public CreateShareLinkResponse createShareLink(final long tenantId, final long teamId, final long projectId,
            final CreateShareLinkRequest request) {
        requireProject(tenantId, teamId, projectId);
        validateExpiry(request.expiresAt());

        final String rawToken = generateRawToken();
        final RoadmapShareLink link = shareLinkRepository.save(
                new RoadmapShareLink(tenantId, teamId, projectId, sha256Hex(rawToken), request.expiresAt()));

        return new CreateShareLinkResponse(link.getId(), rawToken, link.getCreatedAt(), link.getExpiresAt());
    }

    /**
     * Lists every share link ever created for a project (active, expired and revoked alike) —
     * never exposes the token or its hash.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the links, most recently created first (possibly empty)
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public List<ShareLinkResponse> listShareLinks(final long tenantId, final long teamId, final long projectId) {
        requireProject(tenantId, teamId, projectId);
        return shareLinkRepository
                .findAllByProjectIdAndTenantIdAndTeamIdOrderByCreatedAtDesc(projectId, tenantId, teamId)
                .stream()
                .map(ShareLinkResponse::from)
                .toList();
    }

    /**
     * Revokes a share link, ending its read-only access immediately.
     *
     * <p><strong>Idempotent by design</strong>: revoking an already-revoked (or already-expired)
     * link is not an error — the caller's desired end state ("this link no longer grants access")
     * already holds, so the second call simply confirms it rather than surfacing a confusing
     * failure on a race between two authorized editors, or a repeated click.
     *
     * @param tenantId    the requesting tenant's {@code public.tenants.id}
     * @param teamId      the requesting team's {@code public.teams.id}
     * @param projectId   the project id
     * @param shareLinkId the share link id to revoke
     * @throws ProjectNotFoundException     if the project is not visible to the tenant/team
     * @throws ShareLinkNotFoundException if the share link does not resolve on this project
     */
    @Transactional
    public void revokeShareLink(final long tenantId, final long teamId, final long projectId,
            final long shareLinkId) {
        requireProject(tenantId, teamId, projectId);
        final RoadmapShareLink link = shareLinkRepository
                .findByIdAndProjectIdAndTenantIdAndTeamId(shareLinkId, projectId, tenantId, teamId)
                .orElseThrow(() -> new ShareLinkNotFoundException(shareLinkId, projectId));

        if (link.getRevokedAt() == null) {
            link.setRevokedAt(Instant.now());
            shareLinkRepository.save(link);
        }
    }

    // ---- public, unauthenticated read path ---------------------------------------------------------

    /**
     * Resolves a raw share token to its project's read-only roadmap view (lanes + initiatives).
     *
     * <p>Security AC enforcement: a single {@link ShareLinkAccessDeniedException} covers an
     * unknown token, a revoked link and an expired link alike (see that exception's Javadoc for
     * why they are deliberately not distinguished) — never a partial roadmap display, per the AC
     * "pas d'affichage partiel de la roadmap".
     *
     * @param rawToken the raw, unhashed token supplied by the recipient (path variable)
     * @return the project name plus its lanes and initiatives — never any other
     *         project/portfolio's data
     * @throws ShareLinkAccessDeniedException if the token is unknown, or the matching link is
     *                                          revoked or past its expiry
     */
    @Transactional(readOnly = true)
    public RoadmapShareViewResponse viewSharedRoadmap(final String rawToken) {
        final RoadmapShareLink link = shareLinkRepository.findByTokenHash(sha256Hex(rawToken))
                .filter(RoadmapShareLink::isActive)
                .orElseThrow(ShareLinkAccessDeniedException::new);

        // Defensive re-resolution of the project: the FK (ON DELETE CASCADE) guarantees this
        // always succeeds in practice, but never trust a stored id blindly on a public path.
        final Project project = projectRepository
                .findByIdAndTenantIdAndTeamId(link.getProjectId(), link.getTenantId(), link.getTeamId())
                .orElseThrow(ShareLinkAccessDeniedException::new);

        final List<LaneResponse> lanes = roadmapService.listLanes(link.getTenantId(), link.getTeamId(),
                link.getProjectId());
        final List<InitiativeResponse> initiatives = roadmapService.listInitiatives(link.getTenantId(),
                link.getTeamId(), link.getProjectId());

        return new RoadmapShareViewResponse(project.getName(), lanes, initiatives);
    }

    // ---- shared guards --------------------------------------------------------------------------

    /**
     * Resolves the target project within the tenant/team boundary — same isolation check as
     * {@code RoadmapService.requireProject}, re-implemented here (rather than shared) so each
     * service owns its own isolation guard independently, consistent with this module's existing
     * convention.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    private void requireProject(final long tenantId, final long teamId, final long projectId) {
        projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, tenantId, teamId));
    }

    /**
     * Rejects an {@code expiresAt} that is not strictly in the future.
     *
     * @param expiresAt the candidate expiry, or {@code null} (always accepted — no expiry)
     * @throws InvalidShareLinkExpiryException if non-null and not strictly after now
     */
    private static void validateExpiry(final Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new InvalidShareLinkExpiryException("expiresAt must be strictly in the future");
        }
    }

    /**
     * Generates a fresh 256-bit cryptographically secure raw token, hex-encoded.
     *
     * @return a 64-character lowercase hex string
     */
    private static String generateRawToken() {
        final byte[] bytes = new byte[RAW_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Returns the lowercase hex-encoded SHA-256 digest of a raw token — used both to hash a newly
     * generated token before persisting it, and to hash a caller-supplied token before looking it
     * up. The raw token itself is never persisted, never logged.
     *
     * @param rawToken the plaintext token to hash
     * @return 64-character hex string
     */
    private static String sha256Hex(final String rawToken) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

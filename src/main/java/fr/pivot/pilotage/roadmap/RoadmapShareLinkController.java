package fr.pivot.pilotage.roadmap;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the <strong>authenticated</strong> half of the US22.3.5 contract
 * ("Partage & export de la roadmap"): creating, listing and revoking read-only roadmap share
 * links. The public, unauthenticated consultation endpoint lives in a separate controller,
 * {@link RoadmapShareViewController} — deliberately not on this same
 * {@code /tenants/.../projects/{projectId}/...} prefix, since a share-link recipient never has a
 * {@code tenantId}/{@code teamId}/{@code projectId} to supply (see that controller's Javadoc).
 *
 * <p><strong>Additive, package-separate from {@link RoadmapController}</strong> on purpose: two
 * sibling US (now/next/later, milestones) touch that controller in parallel on this same repo —
 * this US stays additive to avoid merge conflicts, at the cost of one small, deliberate
 * duplication: {@link #requireEditAuthorized()} repeats
 * {@code RoadmapController.requireEditAuthorized()} verbatim (two lines) rather than extracting a
 * shared helper that would itself become a third file every parallel agent might touch.
 *
 * <p>Every write here (create, revoke) — and, conservatively, the list endpoint too, since
 * managing a roadmap's sharing surface is itself an editorial action on that roadmap — is gated
 * by {@link RoadmapEditPolicy}, reused as-is from US22.3.1 (per the CLAUDE.md instruction: "réutilise
 * RoadmapEditPolicy"). No business logic here (CLAUDE.md §Standards) — delegated to
 * {@link RoadmapShareService}.
 */
@RestController
@RequestMapping("/tenants/{tenantId}/teams/{teamId}/projects/{projectId}/roadmap/share-links")
public class RoadmapShareLinkController {

    private final RoadmapShareService shareService;
    private final RoadmapEditPolicy editPolicy;

    /**
     * Constructs the controller.
     *
     * @param shareService the share-link business logic (US22.3.5)
     * @param editPolicy   the role-gate extension point for writes, reused from US22.3.1
     */
    public RoadmapShareLinkController(final RoadmapShareService shareService, final RoadmapEditPolicy editPolicy) {
        this.shareService = shareService;
        this.editPolicy = editPolicy;
    }

    /**
     * Creates a new read-only share link for a project's roadmap.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the creation payload (optional expiry)
     * @return {@code 201 Created} with the created link, including the raw token (this one time
     *         only); {@code 400} if {@code expiresAt} is not strictly in the future;
     *         {@code 403} if unauthorized; {@code 404} if the project is not visible
     */
    @PostMapping
    public ResponseEntity<CreateShareLinkResponse> createShareLink(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @Valid @RequestBody final CreateShareLinkRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shareService.createShareLink(tenantId, teamId, projectId, request));
    }

    /**
     * Lists every share link ever created for a project's roadmap (active, expired and revoked
     * alike) — never exposes a token or its hash.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the links, most recently created first; {@code 403} if
     *         unauthorized; {@code 404} if the project is not visible
     */
    @GetMapping
    public ResponseEntity<List<ShareLinkResponse>> listShareLinks(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        requireEditAuthorized();
        return ResponseEntity.ok(shareService.listShareLinks(tenantId, teamId, projectId));
    }

    /**
     * Revokes a share link, ending its read-only access immediately (idempotent — see
     * {@link RoadmapShareService#revokeShareLink}).
     *
     * @param tenantId    the tenant's {@code public.tenants.id}
     * @param teamId      the team's {@code public.teams.id}
     * @param projectId   the project id
     * @param shareLinkId the share link id to revoke
     * @return {@code 204 No Content} on success (whether or not the link was already revoked);
     *         {@code 403} if unauthorized; {@code 404} if the link does not resolve on this project
     */
    @DeleteMapping("/{shareLinkId}")
    public ResponseEntity<Void> revokeShareLink(@PathVariable final long tenantId, @PathVariable final long teamId,
            @PathVariable final long projectId, @PathVariable final long shareLinkId) {
        requireEditAuthorized();
        shareService.revokeShareLink(tenantId, teamId, projectId, shareLinkId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Short-circuits every endpoint on this controller before any service call when the caller is
     * not authorized — mirrors {@code RoadmapController.requireEditAuthorized()} exactly (see the
     * class Javadoc for why this small duplication is deliberate here).
     *
     * @throws RoadmapEditForbiddenException if the current caller is not authorized
     */
    private void requireEditAuthorized() {
        if (!editPolicy.isAuthorized()) {
            throw new RoadmapEditForbiddenException();
        }
    }
}

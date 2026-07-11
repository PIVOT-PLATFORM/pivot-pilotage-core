package fr.pivot.pilotage.roadmap;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the <strong>public</strong>, unauthenticated half of the US22.3.5
 * contract ("Partage & export de la roadmap"): consulting a roadmap read-only via a share link.
 *
 * <p><strong>Deliberately not nested under {@code /tenants/{tenantId}/teams/{teamId}/projects/
 * {projectId}/...}</strong>, unlike every other controller in this package — the whole point of
 * a share link is that the recipient has none of those identifiers, only the opaque token; the
 * {@link RoadmapShareService} resolves tenant/team/project from the token itself. Mounted instead
 * at {@code /public/roadmap-shares/{token}} (behind this module's nginx prefix, the full external
 * path is {@code GET /api/pilotage/public/roadmap-shares/{token}} — see
 * {@code application.yml}'s {@code server.servlet.context-path}).
 *
 * <p><strong>"Public" here means "no {@link RoadmapEditPolicy} gate and no tenant/team/project
 * path segments"</strong> — it does not (yet) mean "bypasses a real authentication layer", since
 * this gap-era module has no such layer at all today (CLAUDE.md §gap, {@code TODO-SETUP.md} §5);
 * every endpoint in this repo is currently reachable without a session. The token itself is this
 * endpoint's only access control, exactly as the backlog note requires ("permettre un accès sans
 * compte au destinataire").
 *
 * <p>Strictly read-only by construction: this controller exposes no mutating method at all, so
 * "no mutation possible via this path" (AC) holds structurally, not just by convention.
 */
@RestController
@RequestMapping("/public/roadmap-shares")
public class RoadmapShareViewController {

    private final RoadmapShareService shareService;

    /**
     * Constructs the controller.
     *
     * @param shareService the share-link business logic (US22.3.5)
     */
    public RoadmapShareViewController(final RoadmapShareService shareService) {
        this.shareService = shareService;
    }

    /**
     * Consults a roadmap read-only via its share token.
     *
     * @param token the raw, unhashed bearer token from the share URL
     * @return {@code 200 OK} with the project's name, lanes and initiatives; {@code 404} with an
     *         explicit {@link ApiError} message if the token is unknown, revoked or expired —
     *         never a partial roadmap
     */
    @GetMapping("/{token}")
    public ResponseEntity<RoadmapShareViewResponse> viewSharedRoadmap(@PathVariable final String token) {
        return ResponseEntity.ok(shareService.viewSharedRoadmap(token));
    }
}

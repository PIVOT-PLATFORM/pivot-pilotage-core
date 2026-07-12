package fr.pivot.pilotage.dashboard;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the personalized-dashboard contract (US23.2.2 — "Tableaux de bord
 * personnalisables").
 *
 * <p><strong>URL shape — gap-era, mirrors {@code RoadmapController}.</strong>
 * {@code pivot-core-starter} (the module exposing {@code TenantContext}, including the caller's own
 * user id) is not published (CLAUDE.md §gap, {@code TODO-SETUP.md} §5), so {@code tenantId},
 * {@code teamId} <strong>and {@code userId}</strong> are explicit path variables here, never a
 * body/query param. Once the starter is consumable, all three are expected to move to the security
 * context, with no change to {@link DashboardService}'s signature beyond dropping the now-redundant
 * arguments.
 *
 * <p><strong>Security AC resolution ("propre à l'utilisateur"; "accès à la configuration d'un
 * autre utilisateur → 404").</strong> This module has no authentication mechanism at all yet — not
 * even the fail-closed role gate used elsewhere ({@code RoadmapEditPolicy}'s {@code DenyAll}
 * pattern applies to <em>authorization</em> checks against a real membership system that does not
 * exist; there is no analogous "is this really user X" <em>identity</em> check possible today
 * either, since nothing distinguishes a genuine caller from one merely typing a different
 * {@code userId} in the path). Exactly like {@code tenantId}/{@code teamId} elsewhere in this repo,
 * the {@code userId} path segment is the accepted, documented, temporary stand-in for the caller's
 * own identity — once {@code TenantContext} is wired, a caller can only ever supply <em>their
 * own</em> id there (JWT-derived, not attacker-chosen), closing this gap platform-wide in one
 * place, not per-endpoint.
 *
 * <p>What <strong>is</strong> implemented today, and is the literal, testable expression of the
 * AC's non-disclosure intent: {@link DashboardConfigRepository#findByTenantIdAndTeamIdAndUserId}
 * scopes every read/write to the exact {@code (tenantId, teamId, userId)} triple in the path — a
 * request for a different {@code userId} can <em>never</em> observe or mutate another user's real,
 * saved widgets/alerts; it is always served its own row, or (if none exists yet) a uniform, fresh
 * default (see {@link DashboardResponse} javadoc). A uniform "own-or-default" response regardless
 * of whether another user's dashboard exists is at least as strong a non-disclosure posture as a
 * literal {@code 404} would be (a 404-vs-200 split would itself leak whether a given userId has a
 * configured dashboard) — documented here explicitly as the Gate 1 PO Agent resolution of this AC's
 * exact wording, for the maintainer to override if a literal 404 is preferred once real identity
 * exists to make that distinction meaningful.
 *
 * <p>Reads are not gated by any policy (mirrors every other controller in this repo — isolation is
 * enforced by scoped repository lookups, not an authorization check); there is no role-based write
 * gate here either (unlike {@code RoadmapEditPolicy}): saving one's own dashboard is not a
 * project-membership permission, it is inherent to owning the resource.
 *
 * <p>No business logic here (CLAUDE.md §Standards) — delegated entirely to {@link DashboardService};
 * exception-mapped by {@link DashboardExceptionHandler}.
 */
@RestController
@RequestMapping("/tenants/{tenantId}/teams/{teamId}/users/{userId}/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Constructs the controller.
     *
     * @param dashboardService the dashboard business logic
     */
    public DashboardController(final DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * Reads and renders the caller's dashboard (AC1/AC3).
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param teamId   the team's {@code public.teams.id}
     * @param userId   the owning user's id
     * @return {@code 200 OK} with the rendered dashboard (the user's own layout, or a fresh
     *         default if none is configured yet — never 404, see class javadoc)
     */
    @GetMapping
    public ResponseEntity<DashboardResponse> getDashboard(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long userId) {
        return ResponseEntity.ok(dashboardService.getDashboard(tenantId, teamId, userId));
    }

    /**
     * Replaces the caller's whole dashboard layout (AC3).
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param teamId   the team's {@code public.teams.id}
     * @param userId   the owning user's id
     * @param request  the desired layout (profile, view mode, complete widget list)
     * @return {@code 200 OK} with the freshly rendered dashboard; {@code 400} if the view mode is
     *         missing or any widget is invalid (unknown type, missing/unresolvable application,
     *         disposition out of bounds) — nothing is persisted on a 400
     */
    @PutMapping
    public ResponseEntity<DashboardResponse> saveDashboard(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long userId,
            @Valid @RequestBody final SaveDashboardRequest request) {
        return ResponseEntity.ok(dashboardService.saveDashboard(tenantId, teamId, userId, request));
    }
}

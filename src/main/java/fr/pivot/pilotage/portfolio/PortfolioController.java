package fr.pivot.pilotage.portfolio;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the consolidated portfolio view (US23.2.1 — "Vue portefeuille
 * consolidée").
 *
 * <p><strong>URL shape — gap-era, mirrors {@code OrganizationProfileController}.</strong> {@code
 * pivot-core-starter} (the module exposing {@code TenantContext}) is not published (CLAUDE.md §gap,
 * {@code TODO-SETUP.md} §5), so {@code tenantId} is an explicit path variable — never a body/query
 * param (CLAUDE.md §Isolation tenant). Unlike {@code RoadmapController} (a single project's
 * roadmap), the portfolio is a <strong>tenant-wide</strong> dashboard spanning every team's
 * applications/projects (AC: "seuls les projets des équipes [plural] du tenant... apparaissent"), so
 * — like {@code OrganizationProfileController} — no {@code teamId}/{@code projectId} path segment is
 * needed here; those identifiers travel inside the response body for the frontend to build each
 * project's drill-down URL.
 *
 * <p>No business logic here (CLAUDE.md §Standards) — the read-right gate is delegated to
 * {@link PortfolioReadPolicy} and the roll-up itself to {@link PortfolioConsolidationService}; both
 * are exception-mapped by {@link PortfolioExceptionHandler}.
 *
 * <p><strong>Drill-down (AC).</strong> This controller returns identifiers only
 * ({@code teamId}/{@code projectId} inside each {@link PortfolioProjectEntry}); the frontend
 * navigates the drill-down to the <strong>existing</strong>
 * {@code fr.pivot.pilotage.roadmap.RoadmapController} project endpoints — no project detail content
 * is ever duplicated here, and that controller's own cross-tenant 404 non-disclosure applies
 * unchanged.
 */
@RestController
@RequestMapping("/tenants/{tenantId}/portfolio")
public class PortfolioController {

    private final PortfolioConsolidationService portfolioConsolidationService;
    private final PortfolioReadPolicy readPolicy;

    /**
     * Constructs the controller.
     *
     * @param portfolioConsolidationService the portfolio roll-up service (US23.2.1)
     * @param readPolicy                    the role-gate extension point for reads (US23.2.1, mirrors
     *                                      {@code RoadmapEditPolicy})
     */
    public PortfolioController(final PortfolioConsolidationService portfolioConsolidationService,
            final PortfolioReadPolicy readPolicy) {
        this.portfolioConsolidationService = portfolioConsolidationService;
        this.readPolicy = readPolicy;
    }

    /**
     * Reads the tenant's consolidated portfolio view.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return {@code 200 OK} with the consolidated portfolio; {@code 403} if the caller has no read
     *         right on the tenant's portfolio
     */
    @GetMapping
    public ResponseEntity<PortfolioResponse> getPortfolio(@PathVariable final long tenantId) {
        if (!readPolicy.isAuthorized()) {
            throw new PortfolioReadForbiddenException();
        }
        return ResponseEntity.ok(portfolioConsolidationService.consolidate(tenantId));
    }
}

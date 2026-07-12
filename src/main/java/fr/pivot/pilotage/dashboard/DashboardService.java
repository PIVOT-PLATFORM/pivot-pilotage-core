package fr.pivot.pilotage.dashboard;

import fr.pivot.pilotage.consolidation.ApplicationConsolidation;
import fr.pivot.pilotage.consolidation.ApplicationConsolidationService;
import fr.pivot.pilotage.consolidation.ApplicationMilestone;
import fr.pivot.pilotage.consolidation.ApplicationNotFoundException;
import fr.pivot.pilotage.consolidation.ProjectPlanningStatus;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped business logic for personalized dashboards (US23.2.2).
 *
 * <p>{@link #getDashboard} renders a user's persisted layout (AC1/AC3): base widget data comes
 * from EN18.9's {@link ApplicationConsolidationService} (already merged — {@code
 * PORTFOLIO_STATUS_SUMMARY}/{@code STRATEGIC_MILESTONES} read it directly, no dependency on the
 * not-yet-merged US23.2.1); tension overlays (AC2) come from every registered
 * {@link PortfolioIndicatorSource} (only the no-op default is wired today, pending the future
 * US23.2.4 météo calc source — see {@link PortfolioIndicatorSource} javadoc for the full
 * decoupling rationale).
 *
 * <p>{@link #saveDashboard} validates the <strong>entire</strong> incoming layout before writing
 * anything (AC error — "system retourne 400... et ne persiste pas": no partial layout is ever
 * left behind by a rejected save).
 *
 * <p><strong>Gap-era explicit arguments, REST implemented.</strong> Per CLAUDE.md §gap and
 * {@code TODO-SETUP.md} §5, {@code tenantId}/{@code teamId}/{@code userId} are explicit arguments
 * here, never taken from a security context that does not exist yet (see
 * {@link DashboardController} for the full rationale, including the security-AC "own dashboard
 * only" resolution).
 */
@Service
public class DashboardService {

    private final DashboardConfigRepository dashboardConfigRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationConsolidationService consolidationService;
    private final List<PortfolioIndicatorSource> indicatorSources;

    /**
     * Constructs the service.
     *
     * @param dashboardConfigRepository tenant/team/user-scoped dashboard repository
     * @param applicationRepository     tenant/team-scoped application repository (EN18.1), used to
     *                                  validate a widget's target application at save time
     * @param consolidationService      EN18.9 per-application roll-up, reused directly for the
     *                                  {@code PORTFOLIO_STATUS_SUMMARY}/{@code STRATEGIC_MILESTONES}
     *                                  widgets' base data
     * @param indicatorSources          all cross-cutting tension sources on the classpath (at least
     *                                  the no-op default); a real threshold engine plugs in post
     *                                  the future US23.2.4
     */
    public DashboardService(final DashboardConfigRepository dashboardConfigRepository,
            final ApplicationRepository applicationRepository,
            final ApplicationConsolidationService consolidationService,
            final List<PortfolioIndicatorSource> indicatorSources) {
        this.dashboardConfigRepository = dashboardConfigRepository;
        this.applicationRepository = applicationRepository;
        this.consolidationService = consolidationService;
        this.indicatorSources = List.copyOf(indicatorSources);
    }

    /**
     * Reads and renders a user's dashboard (AC1/AC3).
     *
     * @param tenantId the requesting tenant's {@code public.tenants.id}
     * @param teamId   the team the dashboard was configured under
     * @param userId   the owning user's id
     * @return the rendered dashboard — the user's persisted layout, or a fresh, never-persisted
     *         default if they have not configured one yet (see {@link DashboardResponse} javadoc);
     *         never another user's real configuration (security AC — see
     *         {@link DashboardConfigRepository#findByTenantIdAndTeamIdAndUserId})
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(final long tenantId, final long teamId, final long userId) {
        return dashboardConfigRepository.findByTenantIdAndTeamIdAndUserId(tenantId, teamId, userId)
                .map(config -> toResponse(tenantId, config))
                .orElseGet(() -> defaultDashboard(userId));
    }

    /**
     * Validates and persists a user's whole dashboard layout in one call (AC3), then returns the
     * freshly rendered result (same shape/rendering path as {@link #getDashboard}, for immediate
     * caller feedback).
     *
     * @param tenantId the requesting tenant's {@code public.tenants.id}
     * @param teamId   the team the dashboard is configured under
     * @param userId   the owning user's id
     * @param request  the desired layout
     * @return the freshly rendered dashboard
     * @throws InvalidDashboardConfigException if the top-level configuration is invalid (missing
     *                                          view mode)
     * @throws InvalidDashboardWidgetException if any widget in the layout is invalid (unknown
     *                                          type, missing/unresolvable application, disposition
     *                                          out of bounds) — the whole request is rejected,
     *                                          nothing is persisted
     */
    @Transactional
    public DashboardResponse saveDashboard(final long tenantId, final long teamId, final long userId,
            final SaveDashboardRequest request) {
        if (request.viewMode() == null) {
            throw new InvalidDashboardConfigException(InvalidDashboardConfigException.CODE_VIEW_MODE_REQUIRED,
                    "A view mode (SYNTHETIC/DETAILED) is required to save a dashboard");
        }
        // request.widgets() is never null: SaveDashboardRequest's canonical constructor already
        // normalizes a null widget list to empty.
        final List<DashboardWidget> validatedWidgets = new ArrayList<>(request.widgets().size());
        for (final SaveDashboardWidgetRequest widgetRequest : request.widgets()) {
            validatedWidgets.add(validateWidget(tenantId, teamId, widgetRequest));
        }

        final DashboardConfig config = dashboardConfigRepository
                .findByTenantIdAndTeamIdAndUserId(tenantId, teamId, userId)
                .orElseGet(() -> new DashboardConfig(tenantId, teamId, userId, request.profile(), request.viewMode()));
        config.replaceWidgets(request.profile(), request.viewMode(), validatedWidgets);

        return toResponse(tenantId, dashboardConfigRepository.save(config));
    }

    // ---- save-time widget validation ----------------------------------------------------------

    private DashboardWidget validateWidget(final long tenantId, final long teamId,
            final SaveDashboardWidgetRequest widgetRequest) {
        if (widgetRequest.widgetType() == null) {
            throw new InvalidDashboardWidgetException(InvalidDashboardWidgetException.CODE_WIDGET_TYPE_REQUIRED,
                    "A widget type is required for every dashboard widget");
        }
        if (widgetRequest.applicationId() == null) {
            throw new InvalidDashboardWidgetException(
                    InvalidDashboardWidgetException.CODE_WIDGET_APPLICATION_REQUIRED,
                    "A target application is required for widget type " + widgetRequest.widgetType());
        }
        requireGridInBounds(widgetRequest);

        final Application application = applicationRepository
                .findByIdAndTenantIdAndTeamId(widgetRequest.applicationId(), tenantId, teamId)
                .orElseThrow(() -> new InvalidDashboardWidgetException(
                        InvalidDashboardWidgetException.CODE_WIDGET_APPLICATION_NOT_FOUND,
                        "No application " + widgetRequest.applicationId() + " visible to tenant " + tenantId
                                + "/team " + teamId));

        return new DashboardWidget(tenantId, teamId, application.getId(), widgetRequest.widgetType(),
                widgetRequest.position(), widgetRequest.gridRow(), widgetRequest.gridColumn(),
                widgetRequest.gridWidth(), widgetRequest.gridHeight());
    }

    /**
     * Validates a widget's grid disposition against the 4-column grid (AC error: "disposition hors
     * bornes") — the DB {@code CHECK} constraints mirror these same bounds as defense-in-depth, but
     * this is the path that produces the caller-facing message.
     */
    private void requireGridInBounds(final SaveDashboardWidgetRequest widgetRequest) {
        final boolean inBounds = widgetRequest.position() >= 0
                && widgetRequest.gridRow() >= 0
                && widgetRequest.gridColumn() >= 0 && widgetRequest.gridColumn() <= 3
                && widgetRequest.gridWidth() >= 1 && widgetRequest.gridWidth() <= 4
                && widgetRequest.gridHeight() >= 1 && widgetRequest.gridHeight() <= 4
                && widgetRequest.gridColumn() + widgetRequest.gridWidth() <= 4;
        if (!inBounds) {
            throw new InvalidDashboardWidgetException(
                    InvalidDashboardWidgetException.CODE_WIDGET_DISPOSITION_OUT_OF_BOUNDS,
                    "Widget disposition out of bounds (grid: 4 columns, row>=0, column 0..3, "
                            + "width/height 1..4, column+width<=4): " + widgetRequest);
        }
    }

    // ---- rendering ------------------------------------------------------------------------------

    private DashboardResponse defaultDashboard(final long userId) {
        return new DashboardResponse(userId, null, DashboardViewMode.SYNTHETIC, List.of(), null);
    }

    private DashboardResponse toResponse(final long tenantId, final DashboardConfig config) {
        final List<DashboardWidgetResponse> widgets = config.getWidgets().stream()
                .sorted(Comparator.comparingInt(DashboardWidget::getPosition))
                .map(widget -> renderWidget(tenantId, widget))
                .toList();
        return new DashboardResponse(config.getUserId(), config.getProfile(), config.getViewMode(), widgets,
                config.getUpdatedAt());
    }

    private DashboardWidgetResponse renderWidget(final long tenantId, final DashboardWidget widget) {
        final DashboardIndicatorView indicator = switch (widget.getWidgetType()) {
            case PORTFOLIO_STATUS_SUMMARY -> renderStatusSummary(tenantId, widget.getApplicationId());
            case STRATEGIC_MILESTONES -> renderMilestones(tenantId, widget.getApplicationId());
            case WEATHER_ALERTS -> renderWeather(tenantId, widget.getApplicationId());
        };
        return new DashboardWidgetResponse(widget.getId(), widget.getWidgetType(), widget.getApplicationId(),
                widget.getPosition(), widget.getGridRow(), widget.getGridColumn(), widget.getGridWidth(),
                widget.getGridHeight(), indicator);
    }

    private DashboardIndicatorView renderStatusSummary(final long tenantId, final long applicationId) {
        final ApplicationConsolidation consolidation;
        try {
            consolidation = consolidationService.consolidate(tenantId, applicationId);
        } catch (final ApplicationNotFoundException ex) {
            return DashboardIndicatorView.unavailable();
        }
        final Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (final ProjectPlanningStatus status : ProjectPlanningStatus.values()) {
            byStatus.put(status.name(), consolidation.projectsByStatus().getOrDefault(status, 0));
        }
        final Optional<PortfolioIndicatorSnapshot> tension =
                resolveIndicator(tenantId, applicationId, PortfolioIndicatorKind.PROGRESS);
        return new DashboardIndicatorView(IndicatorStatus.AVAILABLE,
                tension.map(PortfolioIndicatorSnapshot::level).orElse(AlertLevel.NONE),
                tension.map(PortfolioIndicatorSnapshot::label).orElse(null),
                consolidation.projectCount(), byStatus, null);
    }

    private DashboardIndicatorView renderMilestones(final long tenantId, final long applicationId) {
        final ApplicationConsolidation consolidation;
        try {
            consolidation = consolidationService.consolidate(tenantId, applicationId);
        } catch (final ApplicationNotFoundException ex) {
            return DashboardIndicatorView.unavailable();
        }
        final List<StrategicMilestoneView> milestones = consolidation.strategicMilestones().stream()
                .map(this::toMilestoneView)
                .toList();
        return new DashboardIndicatorView(IndicatorStatus.AVAILABLE, AlertLevel.NONE, null, null, null, milestones);
    }

    private StrategicMilestoneView toMilestoneView(final ApplicationMilestone milestone) {
        return new StrategicMilestoneView(milestone.nodeId(), milestone.projectId(), milestone.name(),
                milestone.fuzzyPeriodStart(), milestone.fuzzyPeriodEnd());
    }

    private DashboardIndicatorView renderWeather(final long tenantId, final long applicationId) {
        return resolveIndicator(tenantId, applicationId, PortfolioIndicatorKind.WEATHER)
                .map(snapshot -> new DashboardIndicatorView(IndicatorStatus.AVAILABLE, snapshot.level(),
                        snapshot.label(), null, null, null))
                .orElseGet(DashboardIndicatorView::unavailable);
    }

    /**
     * Asks every registered {@link PortfolioIndicatorSource} in turn and returns the first
     * non-empty snapshot (deterministic today: only the no-op default is wired, so this always
     * returns empty — a future real source simply starts winning here, no change needed).
     */
    private Optional<PortfolioIndicatorSnapshot> resolveIndicator(final long tenantId, final long applicationId,
            final PortfolioIndicatorKind kind) {
        for (final PortfolioIndicatorSource source : indicatorSources) {
            final Optional<PortfolioIndicatorSnapshot> snapshot = source.indicatorFor(tenantId, applicationId, kind);
            if (snapshot.isPresent()) {
                return snapshot;
            }
        }
        return Optional.empty();
    }
}

package fr.pivot.pilotage.portfolio;

import fr.pivot.pilotage.consolidation.ApplicationConsolidation;
import fr.pivot.pilotage.consolidation.ApplicationConsolidationService;
import fr.pivot.pilotage.consolidation.ProjectPlanningStatus;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Phase;
import fr.pivot.pilotage.schedule.PhaseRepository;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped "consolidated portfolio view" (US23.2.1) — "une vision 360° multi-projets (santé,
 * avancement, phases, jalons et dates clés) avec drill-down" for the direction.
 *
 * <p><strong>Builds directly on EN18.9, reusing its mechanism rather than duplicating it</strong>
 * (per this US's implementation note): {@link #consolidate(long)} lists every
 * {@link fr.pivot.pilotage.project.Application} visible to the tenant and, for each one, calls the
 * existing {@link ApplicationConsolidationService#consolidate(long, long)} <strong>unchanged</strong>
 * to obtain the "jalons" (strategic milestones, each tagged with its {@code projectId} for
 * drill-down) and "dates clés" (temporal window) dimensions — EN18.9's frozen, application-level
 * roll-up is never re-implemented here. This US adds only what EN18.9 deliberately left at
 * application granularity: the per-<strong>project</strong> "santé" and "avancement" indicators (AC:
 * "given un indicateur consolidé (santé, jalon, avancement)... il navigue vers le détail du projet
 * correspondant") and the "phases" dimension, read through the same tenant-scoped repositories EN18.9
 * uses — no inter-module FK, no second store.
 *
 * <p><strong>Security.</strong> Every read goes through a tenant-scoped repository method
 * ({@code findAllByTenantId}, {@code findAllByApplicationIdAndTenantId},
 * {@code findAllByProjectIdAndTenantId}) — a tenant never sees another tenant's application, project,
 * phase or task (AC: "seuls les projets des équipes du tenant... apparaissent"). The read-right gate
 * ({@link PortfolioReadPolicy}) is enforced at {@link PortfolioController}, before this service is
 * ever invoked. The drill-down itself reuses {@code fr.pivot.pilotage.roadmap.RoadmapController}'s
 * existing, already-tested cross-tenant 404 non-disclosure posture — this service only ever hands the
 * frontend a {@code (tenantId, teamId, projectId)} triple belonging to the calling tenant, so no
 * cross-tenant identifier can ever reach that drill-down in the first place.
 *
 * <p><strong>Error AC.</strong> A project with no health indicator reports
 * {@link ProjectHealthStatus#NOT_SET} — never omitted, never a misleading default — see
 * {@link ProjectHealthProvider}.
 */
@Service
public class PortfolioConsolidationService {

    /** Rounding context for the average leaf-task completion ("avancement"), mirrors
     * {@code fr.pivot.pilotage.schedule.projection.PlanProjectionService}'s convention. */
    private static final MathContext PCT_CONTEXT = new MathContext(6);

    private final ApplicationRepository applicationRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskProgressRepository taskProgressRepository;
    private final PhaseRepository phaseRepository;
    private final ApplicationConsolidationService applicationConsolidationService;
    private final List<ProjectHealthProvider> healthProviders;

    /**
     * Constructs the service.
     *
     * @param applicationRepository           tenant-scoped application repository (EN18.1)
     * @param projectRepository               tenant-scoped project repository (EN18.1)
     * @param taskRepository                  tenant-scoped task (temporal graph) repository (EN22.1)
     * @param taskProgressRepository          tenant-scoped task-progress repository (EN22.1a) —
     *                                        source of the "avancement" dimension
     * @param phaseRepository                 tenant-scoped phase repository (EN22.1a) — source of
     *                                        the "phases" dimension
     * @param applicationConsolidationService the EN18.9 per-application consolidation service,
     *                                        reused unchanged for the "jalons"/"dates clés"
     *                                        dimensions
     * @param healthProviders                 all registered {@link ProjectHealthProvider} beans on
     *                                        the classpath (at least the no-op default); the first to
     *                                        answer for a project wins, mirroring
     *                                        {@code ApplicationConsolidationService}'s contributor
     *                                        collection pattern
     */
    public PortfolioConsolidationService(final ApplicationRepository applicationRepository,
            final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final TaskProgressRepository taskProgressRepository, final PhaseRepository phaseRepository,
            final ApplicationConsolidationService applicationConsolidationService,
            final List<ProjectHealthProvider> healthProviders) {
        this.applicationRepository = applicationRepository;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.taskProgressRepository = taskProgressRepository;
        this.phaseRepository = phaseRepository;
        this.applicationConsolidationService = applicationConsolidationService;
        this.healthProviders = List.copyOf(healthProviders);
    }

    /**
     * Consolidates the tenant's whole portfolio: every application visible to the tenant, each
     * grouping its projects with their santé/avancement/phases indicators.
     *
     * @param tenantId the requesting tenant's {@code public.tenants.id} (isolation boundary)
     * @return the consolidated portfolio (possibly with an empty {@code applications} list for a
     *         tenant with no application yet — never {@code null})
     */
    @Transactional(readOnly = true)
    public PortfolioResponse consolidate(final long tenantId) {
        final List<Application> applications = applicationRepository.findAllByTenantId(tenantId);

        final List<PortfolioApplicationEntry> entries = new ArrayList<>();
        for (final Application application : applications) {
            entries.add(toApplicationEntry(tenantId, application));
        }
        entries.sort(Comparator.comparingLong(PortfolioApplicationEntry::applicationId));

        return new PortfolioResponse(tenantId, entries);
    }

    // ---- application grouping (reuses EN18.9 unchanged) ----------------------------------------

    private PortfolioApplicationEntry toApplicationEntry(final long tenantId, final Application application) {
        final ApplicationConsolidation consolidation =
                applicationConsolidationService.consolidate(tenantId, application.getId());

        final List<Project> projects = projectRepository
                .findAllByApplicationIdAndTenantId(application.getId(), tenantId);
        final List<PortfolioProjectEntry> projectEntries = new ArrayList<>();
        for (final Project project : projects) {
            projectEntries.add(toProjectEntry(tenantId, project));
        }
        projectEntries.sort(Comparator.comparingLong(PortfolioProjectEntry::projectId));

        return new PortfolioApplicationEntry(application.getId(), application.getName(), consolidation,
                projectEntries);
    }

    // ---- per-project santé / avancement / phases (this US's addition) --------------------------

    private PortfolioProjectEntry toProjectEntry(final long tenantId, final Project project) {
        final List<Task> tasks = taskRepository.findAllByProjectIdAndTenantId(project.getId(), tenantId);
        final ProjectPlanningStatus planningStatus = ProjectPlanningStatus.deriveFrom(tasks);
        final BigDecimal progressPercent = averageLeafProgress(tenantId, tasks);
        final List<PortfolioPhaseEntry> phases = phasesOf(tenantId, project.getId());
        final ProjectHealthIndicator health = resolveHealth(tenantId, project.getId());

        return new PortfolioProjectEntry(project.getId(), project.getName(), project.getTeamId(),
                planningStatus, health, progressPercent, phases);
    }

    /**
     * Averages the temporal percent-complete of the project's leaf tasks that already carry a
     * {@code task_progress} record ("avancement") — {@link BigDecimal#ZERO} when none does, matching
     * {@code PlanProjectionService}'s zero-weight fallback convention. An unweighted mean is a
     * deliberate simplification for this portfolio-level summary statistic: it is not a replacement
     * for {@code PlanProjectionService}'s charge-weighted per-{@code SUMMARY} rollup (a different,
     * WBS-altitude concern), only a coarser tenant-wide indicator.
     */
    private BigDecimal averageLeafProgress(final long tenantId, final List<Task> tasks) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (final Task task : tasks) {
            if (task.getNodeKind() != NodeKind.LEAF) {
                continue;
            }
            final Optional<TaskProgress> progress =
                    taskProgressRepository.findByTaskIdAndTenantId(task.getId(), tenantId);
            if (progress.isPresent()) {
                sum = sum.add(progress.get().getPercentComplete());
                count++;
            }
        }
        return count == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(count), PCT_CONTEXT);
    }

    private List<PortfolioPhaseEntry> phasesOf(final long tenantId, final long projectId) {
        final List<Phase> phases = new ArrayList<>(
                phaseRepository.findAllByProjectIdAndTenantId(projectId, tenantId));
        phases.sort(Comparator.comparingInt(Phase::getPosition).thenComparing(Phase::getId));

        final List<PortfolioPhaseEntry> result = new ArrayList<>();
        for (final Phase phase : phases) {
            result.add(new PortfolioPhaseEntry(phase.getId(), phase.getName(), phase.getPosition()));
        }
        return result;
    }

    /**
     * Resolves a project's health indicator via the first {@link ProjectHealthProvider} that answers
     * for it, or the explicit {@link ProjectHealthIndicator#notSet()} when none does (error AC).
     */
    private ProjectHealthIndicator resolveHealth(final long tenantId, final long projectId) {
        for (final ProjectHealthProvider provider : healthProviders) {
            final Optional<ProjectHealthIndicator> indicator = provider.healthOf(tenantId, projectId);
            if (indicator.isPresent()) {
                return indicator.get();
            }
        }
        return ProjectHealthIndicator.notSet();
    }
}

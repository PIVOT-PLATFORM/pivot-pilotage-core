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
import fr.pivot.pilotage.schedule.TemporalPrecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PortfolioConsolidationService} with mocked repositories and a mocked
 * {@link ApplicationConsolidationService} (US23.2.1). Cover: the portfolio reusing EN18.9's
 * per-application roll-up unchanged (jalons/dates clés), the per-project santé (health SPI, with
 * the explicit {@code NOT_SET} error-AC fallback), avancement (leaf-task progress average) and
 * phases dimensions, tenant-scoped reads only, and deterministic ordering.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioConsolidationServiceTest {

    private static final long TENANT = 7L;
    private static final long TEAM = 5L;
    private static final Instant ANCHOR = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    @Mock private ApplicationRepository applicationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskProgressRepository taskProgressRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private ApplicationConsolidationService applicationConsolidationService;

    private static void setId(final Object entity, final long id) {
        try {
            final Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Application application(final long id, final String name) {
        final Application app = new Application(TENANT, TEAM, name, ANCHOR);
        setId(app, id);
        return app;
    }

    private Project project(final Application app, final long id, final String name) {
        final Project p = new Project(app, TENANT, TEAM, name, ANCHOR);
        setId(p, id);
        return p;
    }

    private Task leafTask(final long id) {
        final Task t = new Task(TENANT, TEAM, 0L, 0, "leaf", NodeKind.LEAF, false, TemporalPrecision.DAY, 0);
        setId(t, id);
        return t;
    }

    private Phase phase(final long id, final String name, final int position) {
        final Phase p = new Phase(TENANT, TEAM, 1L, null, name, position);
        setId(p, id);
        return p;
    }

    private PortfolioConsolidationService serviceWith(final ProjectHealthProvider... providers) {
        return new PortfolioConsolidationService(applicationRepository, projectRepository, taskRepository,
                taskProgressRepository, phaseRepository, applicationConsolidationService, List.of(providers));
    }

    private ApplicationConsolidation emptyConsolidation(final long appId, final String name) {
        return new ApplicationConsolidation(appId, name, TENANT, 0, Map.of(), null, null, List.of(), List.of());
    }

    // -------- AC: consolidated view groups applications, reusing EN18.9's roll-up unchanged ------

    @Test
    void consolidate_reusesApplicationConsolidationServiceUnchanged() {
        final Application app = application(1L, "Billing");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(app));
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of());
        final ApplicationConsolidation consolidation = new ApplicationConsolidation(1L, "Billing", TENANT, 3,
                Map.of(ProjectPlanningStatus.SCHEDULED, 3), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                List.of(), List.of());
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(consolidation);

        final PortfolioResponse response = serviceWith().consolidate(TENANT);

        assertThat(response.tenantId()).isEqualTo(TENANT);
        assertThat(response.applications()).hasSize(1);
        assertThat(response.applications().get(0).consolidation()).isSameAs(consolidation);
        verify(applicationConsolidationService).consolidate(TENANT, 1L);
    }

    // -------- AC: multiple applications are aggregated, ordered deterministically -----------------

    @Test
    void consolidate_multipleApplications_orderedByApplicationId() {
        final Application appB = application(2L, "Billing");
        final Application appA = application(1L, "Analytics");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(appB, appA));
        when(projectRepository.findAllByApplicationIdAndTenantId(2L, TENANT)).thenReturn(List.of());
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of());
        when(applicationConsolidationService.consolidate(TENANT, 2L)).thenReturn(emptyConsolidation(2L, "Billing"));
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(emptyConsolidation(1L, "Analytics"));

        final PortfolioResponse response = serviceWith().consolidate(TENANT);

        assertThat(response.applications()).extracting(PortfolioApplicationEntry::applicationId)
                .containsExactly(1L, 2L);
    }

    // -------- AC: no application yet → empty portfolio, never an exception -------------------------

    @Test
    void consolidate_tenantWithNoApplication_returnsEmptyPortfolio() {
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of());

        final PortfolioResponse response = serviceWith().consolidate(TENANT);

        assertThat(response.applications()).isEmpty();
    }

    // -------- Error AC: a project with no health indicator reports the explicit NOT_SET -----------

    @Test
    void consolidate_projectWithNoHealthProvider_reportsExplicitNotSet() {
        final Application app = application(1L, "Billing");
        final Project p1 = project(app, 10L, "v1");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(app));
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(emptyConsolidation(1L, "Billing"));
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of(p1));
        when(taskRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());
        when(phaseRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());

        // Only the no-op-equivalent (empty) provider is wired.
        final PortfolioResponse response = serviceWith((tenantId, projectId) -> Optional.empty())
                .consolidate(TENANT);

        final PortfolioProjectEntry entry = response.applications().get(0).projects().get(0);
        assertThat(entry.health()).isEqualTo(ProjectHealthIndicator.notSet());
        assertThat(entry.health().status()).isEqualTo(ProjectHealthStatus.NOT_SET);
    }

    // -------- AC: a wired health provider's indicator surfaces on the matching project ------------

    @Test
    void consolidate_healthProviderAnswers_surfacesItsIndicator() {
        final Application app = application(1L, "Billing");
        final Project p1 = project(app, 10L, "v1");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(app));
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(emptyConsolidation(1L, "Billing"));
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of(p1));
        when(taskRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());
        when(phaseRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());

        final ProjectHealthProvider atRisk = (tenantId, projectId) ->
                projectId == 10L ? Optional.of(new ProjectHealthIndicator(ProjectHealthStatus.AT_RISK))
                        : Optional.empty();

        final PortfolioResponse response = serviceWith(atRisk).consolidate(TENANT);

        final PortfolioProjectEntry entry = response.applications().get(0).projects().get(0);
        assertThat(entry.health().status()).isEqualTo(ProjectHealthStatus.AT_RISK);
    }

    // -------- AC: avancement — average completion of leaf tasks carrying a progress record --------

    @Test
    void consolidate_progressPercent_averagesLeafTaskProgress() {
        final Application app = application(1L, "Billing");
        final Project p1 = project(app, 10L, "v1");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(app));
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(emptyConsolidation(1L, "Billing"));
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of(p1));

        final Task leaf1 = leafTask(1L);
        final Task leaf2 = leafTask(2L);
        // A summary node must never be folded into the average (it is not a leaf).
        final Task summary = new Task(TENANT, TEAM, 0L, 0, "phase", NodeKind.SUMMARY, false,
                TemporalPrecision.DAY, 0);
        setId(summary, 3L);
        when(taskRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of(leaf1, leaf2, summary));
        when(taskProgressRepository.findByTaskIdAndTenantId(1L, TENANT))
                .thenReturn(Optional.of(new TaskProgress(TENANT, TEAM, 1L, BigDecimal.valueOf(20))));
        when(taskProgressRepository.findByTaskIdAndTenantId(2L, TENANT))
                .thenReturn(Optional.of(new TaskProgress(TENANT, TEAM, 2L, BigDecimal.valueOf(80))));
        lenient().when(taskProgressRepository.findByTaskIdAndTenantId(3L, TENANT)).thenReturn(Optional.empty());
        when(phaseRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());

        final PortfolioResponse response = serviceWith().consolidate(TENANT);

        final PortfolioProjectEntry entry = response.applications().get(0).projects().get(0);
        assertThat(entry.progressPercent()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    // -------- AC: avancement — a leaf task with no progress record yet is excluded from the -------
    // -------- average, never treated as 0% (branch coverage on the isPresent() check) -------------

    @Test
    void consolidate_someLeafTasksWithoutProgress_averagesOnlyTheOnesWithARecord() {
        final Application app = application(1L, "Billing");
        final Project p1 = project(app, 10L, "v1");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(app));
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(emptyConsolidation(1L, "Billing"));
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of(p1));

        final Task leafWithProgress = leafTask(1L);
        final Task leafWithoutProgress = leafTask(2L);
        when(taskRepository.findAllByProjectIdAndTenantId(10L, TENANT))
                .thenReturn(List.of(leafWithProgress, leafWithoutProgress));
        when(taskProgressRepository.findByTaskIdAndTenantId(1L, TENANT))
                .thenReturn(Optional.of(new TaskProgress(TENANT, TEAM, 1L, BigDecimal.valueOf(30))));
        when(taskProgressRepository.findByTaskIdAndTenantId(2L, TENANT)).thenReturn(Optional.empty());
        when(phaseRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());

        final PortfolioResponse response = serviceWith().consolidate(TENANT);

        final PortfolioProjectEntry entry = response.applications().get(0).projects().get(0);
        // Only leafWithProgress (30) counts — leafWithoutProgress is excluded, not folded in as 0.
        assertThat(entry.progressPercent()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    void consolidate_noLeafTaskProgress_progressPercentIsZero() {
        final Application app = application(1L, "Billing");
        final Project p1 = project(app, 10L, "v1");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(app));
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(emptyConsolidation(1L, "Billing"));
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of(p1));
        when(taskRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());
        when(phaseRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());

        final PortfolioResponse response = serviceWith().consolidate(TENANT);

        final PortfolioProjectEntry entry = response.applications().get(0).projects().get(0);
        assertThat(entry.progressPercent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -------- AC: phases dimension, ordered by display position -------------------------------------

    @Test
    void consolidate_phases_orderedByPosition() {
        final Application app = application(1L, "Billing");
        final Project p1 = project(app, 10L, "v1");
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of(app));
        when(applicationConsolidationService.consolidate(TENANT, 1L)).thenReturn(emptyConsolidation(1L, "Billing"));
        when(projectRepository.findAllByApplicationIdAndTenantId(1L, TENANT)).thenReturn(List.of(p1));
        when(taskRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of());
        when(phaseRepository.findAllByProjectIdAndTenantId(10L, TENANT)).thenReturn(List.of(
                phase(200L, "Clôture", 1), phase(100L, "Cadrage", 0)));

        final PortfolioResponse response = serviceWith().consolidate(TENANT);

        final PortfolioProjectEntry entry = response.applications().get(0).projects().get(0);
        assertThat(entry.phases()).extracting(PortfolioPhaseEntry::name).containsExactly("Cadrage", "Clôture");
    }

    // -------- Security: only tenant-scoped repository reads are used ------------------------------

    @Test
    void consolidate_onlyReadsThroughTenantScopedRepositories() {
        when(applicationRepository.findAllByTenantId(TENANT)).thenReturn(List.of());

        serviceWith().consolidate(TENANT);

        verify(applicationRepository).findAllByTenantId(TENANT);
    }
}

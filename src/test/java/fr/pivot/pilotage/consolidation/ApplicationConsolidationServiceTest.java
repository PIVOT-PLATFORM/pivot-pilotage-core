package fr.pivot.pilotage.consolidation;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationConsolidationService} with mocked repositories (EN18.9). Cover
 * the pilotage-owned aggregate (project count, projects per derived status, temporal window, unified
 * strategic milestones), the cross-module SPI seam (no-op default vs a fake contributor), the
 * {@code project → application} traceability wiring and the unknown/cross-tenant application error.
 * All reads go through tenant-scoped repositories — no inter-module FK is ever traversed. Timestamps
 * are anchored in the past, never {@code now()}.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationConsolidationServiceTest {

    private static final long TENANT = 7L;
    private static final long APP = 42L;
    private static final Instant ANCHOR = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant MON_0900 =
            LocalDate.of(2024, 2, 5).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
    private static final Instant FRI_1700 =
            LocalDate.of(2024, 2, 9).atStartOfDay(ZoneOffset.UTC).plusHours(17).toInstant();

    @Mock private ApplicationRepository applicationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private TaskRepository taskRepository;

    private static void setId(final Object entity, final long id) {
        try {
            final Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Application application() {
        final Application app = new Application(TENANT, "Billing", ANCHOR);
        setId(app, APP);
        return app;
    }

    private Project project(final long id) {
        final Application app = application();
        final Project p = new Project(app, TENANT, "v" + id, ANCHOR);
        setId(p, id);
        return p;
    }

    private Task leaf(final long id, final Instant start) {
        final Task t = new Task(TENANT, 0L, 0, "leaf", NodeKind.LEAF, false, TemporalPrecision.DAY, 0);
        setId(t, id);
        t.setStartDate(start);
        return t;
    }

    private Task sharedMilestone(final long id, final LocalDate fuzzyStart, final LocalDate fuzzyEnd) {
        final Task t = new Task(TENANT, 0L, 0, "Go-live", NodeKind.MILESTONE, true, TemporalPrecision.DAY, 0);
        setId(t, id);
        t.setFuzzyPeriodStart(fuzzyStart);
        t.setFuzzyPeriodEnd(fuzzyEnd);
        return t;
    }

    private ApplicationConsolidationService serviceWith(final ApplicationDataContributor... extra) {
        final java.util.List<ApplicationDataContributor> all = new java.util.ArrayList<>();
        all.add(new NoOpApplicationDataContributor());
        all.addAll(List.of(extra));
        return new ApplicationConsolidationService(applicationRepository, projectRepository,
                taskRepository, all);
    }

    // -------- AC: N projects → correct aggregates (count, window, unified milestones) -----------

    @Test
    void consolidate_aggregatesCountWindowStatusAndUnifiedMilestones() {
        final Project p1 = project(100L);
        final Project p2 = project(200L);
        final Project p3 = project(300L);
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.of(application()));
        when(projectRepository.findAllByApplicationIdAndTenantId(APP, TENANT))
                .thenReturn(List.of(p1, p2, p3));

        // p1: scheduled leaf + a shared milestone widening the window at the roadmap altitude
        when(taskRepository.findAllByProjectIdAndTenantId(100L, TENANT)).thenReturn(List.of(
                leaf(1L, MON_0900),
                sharedMilestone(2L, LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 31))));
        // p2: a task without any precise window → PLANNED, plus its own shared milestone
        when(taskRepository.findAllByProjectIdAndTenantId(200L, TENANT)).thenReturn(List.of(
                new Task(TENANT, 0L, 0, "planned", NodeKind.LEAF, false, TemporalPrecision.DAY, 0),
                sharedMilestone(3L, LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 30))));
        // p3: no task at all → EMPTY
        when(taskRepository.findAllByProjectIdAndTenantId(300L, TENANT)).thenReturn(List.of());

        final ApplicationConsolidation c = serviceWith().consolidate(TENANT, APP);

        assertThat(c.applicationId()).isEqualTo(APP);
        assertThat(c.applicationName()).isEqualTo("Billing");
        assertThat(c.tenantId()).isEqualTo(TENANT);
        assertThat(c.projectCount()).isEqualTo(3);
        assertThat(c.projectsByStatus()).isEqualTo(Map.of(
                ProjectPlanningStatus.SCHEDULED, 1,
                ProjectPlanningStatus.PLANNED, 1,
                ProjectPlanningStatus.EMPTY, 1));
        // window = min start (Jan 15 fuzzy) .. max finish (Apr 30 fuzzy)
        assertThat(c.windowStart()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(c.windowFinish()).isEqualTo(LocalDate.of(2024, 4, 30));
        // union of both projects' shared milestones, ordered by project then node id
        assertThat(c.strategicMilestones()).extracting(ApplicationMilestone::nodeId)
                .containsExactly(2L, 3L);
        assertThat(c.strategicMilestones()).extracting(ApplicationMilestone::projectId)
                .containsExactly(100L, 200L);
        assertThat(c.contributions()).isEmpty();
    }

    // -------- AC: SPI no-op default → pilotage aggregate alone ----------------------------------

    @Test
    void consolidate_noOpContributor_yieldsNoCrossModuleAggregate() {
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.of(application()));
        when(projectRepository.findAllByApplicationIdAndTenantId(APP, TENANT)).thenReturn(List.of());

        final ApplicationConsolidation c = serviceWith().consolidate(TENANT, APP);

        assertThat(c.projectCount()).isZero();
        assertThat(c.contributions()).isEmpty();
    }

    // -------- AC: a fake contributor → its aggregate appears (bus extension point) --------------

    @Test
    void consolidate_fakeContributor_surfacesItsAggregate() {
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.of(application()));
        when(projectRepository.findAllByApplicationIdAndTenantId(APP, TENANT)).thenReturn(List.of());

        final ApplicationDataContributor budget = (t, a) ->
                Optional.of(new ApplicationAggregateContribution("budget",
                        Map.of("consumedRatio", 0.42)));

        final ApplicationConsolidation c = serviceWith(budget).consolidate(TENANT, APP);

        assertThat(c.contributions()).hasSize(1);
        assertThat(c.contributions().get(0).moduleId()).isEqualTo("budget");
        assertThat(c.contributions().get(0).metrics()).containsEntry("consumedRatio", 0.42);
    }

    // -------- AC: the SPI is invoked with the exact (tenant, application) pair ------------------

    @Test
    void consolidate_invokesContributorWithTenantAndApplication() {
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.of(application()));
        when(projectRepository.findAllByApplicationIdAndTenantId(APP, TENANT)).thenReturn(List.of());

        final ApplicationDataContributor spy = mock(ApplicationDataContributor.class);
        when(spy.contribute(TENANT, APP)).thenReturn(Optional.empty());

        serviceWith(spy).consolidate(TENANT, APP);

        verify(spy).contribute(TENANT, APP);
    }

    // -------- Error/Security: unknown or cross-tenant application → ApplicationNotFoundException -

    @Test
    void consolidate_unknownOrCrossTenantApplication_throwsAndReadsNoProjects() {
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWith().consolidate(TENANT, APP))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining(String.valueOf(APP));

        // Isolation: no project/task read path is even reached for an invisible application.
        verify(projectRepository, never()).findAllByApplicationIdAndTenantId(any(), any());
    }

    // -------- project → application resolution (deterministic single parent) --------------------

    @Test
    void resolver_resolvesSingleParentApplication() {
        final Project p = project(555L);
        when(projectRepository.findByIdAndTenantId(555L, TENANT)).thenReturn(Optional.of(p));

        final ProjectApplicationResolver resolver = new ProjectApplicationResolver(projectRepository);

        assertThat(resolver.resolveApplicationId(TENANT, 555L)).isEqualTo(APP);
    }

    @Test
    void resolver_unknownOrCrossTenantProject_throws() {
        when(projectRepository.findByIdAndTenantId(999L, TENANT)).thenReturn(Optional.empty());
        final ProjectApplicationResolver resolver = new ProjectApplicationResolver(projectRepository);

        assertThatThrownBy(() -> resolver.resolveApplicationId(TENANT, 999L))
                .isInstanceOf(ProjectNotFoundException.class)
                .hasMessageContaining("999");
    }

    // -------- precise-date fallback for the window (no fuzzy bounds) ----------------------------

    @Test
    void consolidate_preciseDatesFoldToUtcDayWhenNoFuzzyBounds() {
        final Project p1 = project(100L);
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.of(application()));
        when(projectRepository.findAllByApplicationIdAndTenantId(APP, TENANT)).thenReturn(List.of(p1));

        final Task withDates = leaf(1L, MON_0900);
        withDates.setFinishDate(FRI_1700);
        lenient().when(taskRepository.findAllByProjectIdAndTenantId(100L, TENANT))
                .thenReturn(List.of(withDates));

        final ApplicationConsolidation c = serviceWith().consolidate(TENANT, APP);

        assertThat(c.windowStart()).isEqualTo(LocalDate.of(2024, 2, 5));
        assertThat(c.windowFinish()).isEqualTo(LocalDate.of(2024, 2, 9));
        assertThat(c.projectsByStatus()).containsEntry(ProjectPlanningStatus.SCHEDULED, 1);
        assertThat(c.strategicMilestones()).isEmpty();
    }
}

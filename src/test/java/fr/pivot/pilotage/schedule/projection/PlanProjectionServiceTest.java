package fr.pivot.pilotage.schedule.projection;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.DependencyLinkType;
import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlanProjectionService} with mocked repositories (EN22.1c, frozen contract
 * §c). Cover the shared-milestone non-divergence invariant (same id in macro and detail), the
 * altitude-driven projection difference (fuzzy vs precise), the derived summary rollup, the
 * Now/Next/Later buckets, the default-altitude seam and tenant isolation (empty for a foreign
 * tenant). All timestamps are anchored in the past — never {@code now()}.
 */
@ExtendWith(MockitoExtension.class)
class PlanProjectionServiceTest {

    private static final long TENANT = 7L;
    private static final long TEAM = 42L;
    private static final long PROJECT = 100L;
    private static final Instant MON_0900 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
    private static final Instant WED_1700 =
            LocalDate.of(2024, 1, 3).atStartOfDay(ZoneOffset.UTC).plusHours(17).toInstant();
    private static final LocalDate Q1_START = LocalDate.of(2024, 1, 1);
    private static final LocalDate Q1_END = LocalDate.of(2024, 3, 31);

    @Mock private ProjectRepository projectRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskDependencyRepository dependencyRepository;
    @Mock private TaskProgressRepository progressRepository;
    @Mock private AssignmentRepository assignmentRepository;

    private PlanProjectionService service;

    @BeforeEach
    void setUp() {
        service = new PlanProjectionService(projectRepository, taskRepository, dependencyRepository,
                progressRepository, assignmentRepository, new FixedDefaultAltitudeProvider());
    }

    private static void setId(final Object entity, final long id) {
        try {
            final Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Project project() {
        final Project p = new Project(null, TENANT, TEAM, "P", MON_0900);
        setId(p, PROJECT);
        return p;
    }

    private Task task(final long id, final int pos, final NodeKind kind, final boolean shared) {
        final Task t = new Task(TENANT, TEAM, PROJECT, pos, "T" + id, kind, shared,
                TemporalPrecision.DAY, 3);
        setId(t, id);
        return t;
    }

    private void wireEmptyRelations() {
        lenient().when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(assignmentRepository.findAllByTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(progressRepository.findByTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
    }

    // -------- AC: shared milestone → same id in macro AND detail (non-divergence) -------------

    @Test
    void sharedMilestone_hasSameIdInMacroAndDetail() {
        final Task milestone = task(1, 0, NodeKind.MILESTONE, true);
        milestone.setStartDate(MON_0900);
        milestone.setFinishDate(MON_0900);
        milestone.setFuzzyPeriodStart(Q1_START);
        milestone.setFuzzyPeriodEnd(Q1_START);
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.of(project()));
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT))
                .thenReturn(List.of(milestone));
        wireEmptyRelations();

        final PlanView macro =
                service.project(PROJECT, TENANT, Altitude.MACRO, Layout.TIMELINE).orElseThrow();
        final PlanView detail =
                service.project(PROJECT, TENANT, Altitude.DETAIL, Layout.GANTT).orElseThrow();

        assertThat(macro.nodes()).extracting(PlanNodeView::nodeId).containsExactly(1L);
        assertThat(detail.nodes()).extracting(PlanNodeView::nodeId).containsExactly(1L);
        // Same id, one record — the milestone is never duplicated.
        assertThat(macro.nodes().get(0).nodeId()).isEqualTo(detail.nodes().get(0).nodeId());
        // Macro reads fuzzy bounds; detail reads precise dates.
        assertThat(macro.nodes().get(0).fuzzyPeriodStart()).isEqualTo(Q1_START);
        assertThat(detail.nodes().get(0).startDate()).isEqualTo(MON_0900);
    }

    // -------- AC: same node projects differently per altitude, id stable ----------------------

    @Test
    void sameNode_projectsFuzzyOrPrecise_idStable() {
        final Task summary = task(1, 0, NodeKind.SUMMARY, true);
        summary.setFuzzyPeriodStart(Q1_START);
        summary.setFuzzyPeriodEnd(Q1_END);
        final Task leaf = task(2, 1, NodeKind.LEAF, false);
        leaf.setParentTaskId(1L);
        leaf.setStartDate(MON_0900);
        leaf.setFinishDate(WED_1700);
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.of(project()));
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT))
                .thenReturn(List.of(summary, leaf));
        wireEmptyRelations();

        final PlanView macro =
                service.project(PROJECT, TENANT, Altitude.MACRO, Layout.TIMELINE).orElseThrow();
        final PlanView detail =
                service.project(PROJECT, TENANT, Altitude.DETAIL, Layout.GANTT).orElseThrow();

        // Macro: only the summary (leaf stays out of roadmap); fuzzy bounds present.
        assertThat(macro.nodes()).extracting(PlanNodeView::nodeId).containsExactly(1L);
        assertThat(macro.nodes().get(0).fuzzyPeriodStart()).isEqualTo(Q1_START);
        // Detail: full tree; leaf carries precise dates. Summary id unchanged across altitudes.
        assertThat(detail.nodes()).extracting(PlanNodeView::nodeId).containsExactly(1L, 2L);
        assertThat(detail.nodes().get(1).startDate()).isEqualTo(MON_0900);
    }

    // -------- AC: summary rollup is derived in projection (start=min, finish=max, Σ, weighted) -

    @Test
    void summaryRollup_isDerivedNotStored() {
        final Task summary = task(1, 0, NodeKind.SUMMARY, true);
        final Task leafA = task(2, 0, NodeKind.LEAF, false);
        leafA.setParentTaskId(1L);
        leafA.setStartDate(MON_0900);
        leafA.setFinishDate(MON_0900.plusSeconds(3600));
        leafA.setCritical(Boolean.TRUE);
        final Task leafB = task(3, 1, NodeKind.LEAF, false);
        leafB.setParentTaskId(1L);
        leafB.setStartDate(MON_0900.plusSeconds(7200));
        leafB.setFinishDate(WED_1700);
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.of(project()));
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT))
                .thenReturn(List.of(summary, leafA, leafB));
        lenient().when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        when(assignmentRepository.findAllByTaskIdAndTenantId(2L, TENANT)).thenReturn(List.of(
                assignment(2L, 240, "100.00")));
        when(assignmentRepository.findAllByTaskIdAndTenantId(3L, TENANT)).thenReturn(List.of(
                assignment(3L, 480, "300.00")));
        when(progressRepository.findByTaskIdAndTenantId(2L, TENANT))
                .thenReturn(Optional.of(progress(2L, "100.00")));
        when(progressRepository.findByTaskIdAndTenantId(3L, TENANT))
                .thenReturn(Optional.of(progress(3L, "50.00")));

        final PlanView detail =
                service.project(PROJECT, TENANT, Altitude.DETAIL, Layout.GANTT).orElseThrow();
        final SummaryAggregate agg = detail.aggregates().get(1L);

        assertThat(agg.rollupStart()).isEqualTo(MON_0900);           // min
        assertThat(agg.rollupFinish()).isEqualTo(WED_1700);          // max
        assertThat(agg.totalWorkMinutes()).isEqualTo(720L);          // Σ 240 + 480
        assertThat(agg.totalCostAmount()).isEqualByComparingTo("400.00"); // Σ 100 + 300
        assertThat(agg.critical()).isTrue();                          // ≥1 leaf critical
        assertThat(agg.leafCount()).isEqualTo(2);
        // charge-weighted %: (100*240 + 50*480) / 720 = 66.66…
        assertThat(agg.percentComplete()).isEqualByComparingTo(new BigDecimal("66.6667"));
        // The summary node view carries the rollup dates, flagged aggregated.
        final PlanNodeView summaryView = detail.nodes().get(0);
        assertThat(summaryView.aggregated()).isTrue();
        assertThat(summaryView.startDate()).isEqualTo(MON_0900);
        assertThat(summaryView.critical()).isTrue();
    }

    // -------- AC: Now/Next/Later buckets group by horizon --------------------------------------

    @Test
    void macroBuckets_groupByHorizon() {
        final Task now = task(1, 0, NodeKind.SUMMARY, true);
        now.setHorizon(Horizon.NOW);
        final Task later = task(2, 1, NodeKind.SUMMARY, true);
        later.setHorizon(Horizon.LATER);
        final Task unbucketed = task(3, 2, NodeKind.SUMMARY, true);
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.of(project()));
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT))
                .thenReturn(List.of(now, later, unbucketed));
        wireEmptyRelations();

        final PlanView view =
                service.project(PROJECT, TENANT, Altitude.MACRO, Layout.BUCKETS).orElseThrow();

        assertThat(view.buckets().get(Horizon.NOW)).extracting(PlanNodeView::nodeId).containsExactly(1L);
        assertThat(view.buckets().get(Horizon.LATER)).extracting(PlanNodeView::nodeId).containsExactly(2L);
        assertThat(view.buckets()).doesNotContainKey(Horizon.NEXT); // node 3 is unbucketised
    }

    // -------- AC: default altitude via the EN18.10 seam ---------------------------------------

    @Test
    void defaultProjection_usesSeamAltitude() {
        final Task milestone = task(1, 0, NodeKind.MILESTONE, true);
        milestone.setStartDate(MON_0900);
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.of(project()));
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT))
                .thenReturn(List.of(milestone));
        wireEmptyRelations();

        // FixedDefaultAltitudeProvider → MACRO, so the natural layout is TIMELINE.
        final PlanView view = service.project(PROJECT, TENANT).orElseThrow();
        assertThat(view.altitude()).isEqualTo(Altitude.MACRO);
        assertThat(view.layout()).isEqualTo(Layout.TIMELINE);
    }

    // -------- AC: detail projection carries dependency edges ----------------------------------

    @Test
    void detailProjection_carriesDependencyEdges() {
        final Task a = task(1, 0, NodeKind.LEAF, false);
        final Task b = task(2, 1, NodeKind.LEAF, false);
        when(projectRepository.findByIdAndTenantId(PROJECT, TENANT)).thenReturn(Optional.of(project()));
        when(taskRepository.findAllByProjectIdAndTenantId(PROJECT, TENANT)).thenReturn(List.of(a, b));
        lenient().when(assignmentRepository.findAllByTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(progressRepository.findByTaskIdAndTenantId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        final TaskDependency dep = new TaskDependency(TENANT, TEAM, 1L, 2L, DependencyLinkType.FS, 30);
        setId(dep, 9L);
        when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(1L, TENANT))
                .thenReturn(List.of(dep));
        lenient().when(dependencyRepository.findAllByPredecessorTaskIdAndTenantId(2L, TENANT))
                .thenReturn(List.of());

        final PlanView detail =
                service.project(PROJECT, TENANT, Altitude.DETAIL, Layout.GANTT).orElseThrow();
        assertThat(detail.dependencies()).hasSize(1);
        assertThat(detail.dependencies().get(0).edgeId()).isEqualTo(9L);
        assertThat(detail.dependencies().get(0).linkType()).isEqualTo(DependencyLinkType.FS);
        assertThat(detail.dependencies().get(0).lagMinutes()).isEqualTo(30L);

        // Macro view carries no edges.
        final PlanView macro =
                service.project(PROJECT, TENANT, Altitude.MACRO, Layout.TIMELINE).orElseThrow();
        assertThat(macro.dependencies()).isEmpty();
    }

    // -------- Security: a foreign tenant's project is not projected (empty ⇒ 404) --------------

    @Test
    void foreignTenant_projectNotProjected() {
        when(projectRepository.findByIdAndTenantId(PROJECT, 999L)).thenReturn(Optional.empty());
        assertThat(service.project(PROJECT, 999L, Altitude.DETAIL, Layout.GANTT)).isEmpty();
        assertThat(service.project(PROJECT, 999L)).isEmpty();
    }

    private Assignment assignment(final long taskId, final int workMinutes, final String cost) {
        final Assignment a = new Assignment(TENANT, TEAM, taskId, "r", new BigDecimal("100.00"));
        a.setWorkMinutes(workMinutes);
        a.setCostAmount(new BigDecimal(cost));
        return a;
    }

    private TaskProgress progress(final long taskId, final String pct) {
        return new TaskProgress(TENANT, TEAM, taskId, new BigDecimal(pct));
    }
}

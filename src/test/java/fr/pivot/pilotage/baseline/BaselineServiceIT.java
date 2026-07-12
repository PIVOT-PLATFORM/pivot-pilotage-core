package fr.pivot.pilotage.baseline;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link BaselineService} (US22.4.9 — Baselines multiples &amp; analyse des
 * écarts) against a real PostgreSQL 18 (Testcontainers), this module's Flyway migration applied.
 * One test (or small group) per behavioural/error/security AC: freezing dates/duration/work/cost on
 * pose, the 11-slot MS-Project-parity limit, overwrite ("écraser"), delete, listing, écarts (jours/
 * %/coût) against the current graph, evolution between two baselines, and cross-tenant/team 404
 * non-disclosure. {@code public.tenants}/{@code public.teams} are seeded before Flyway via
 * {@link PlatformSchemaTestSupport}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class BaselineServiceIT {

    private static final Instant T0 = Instant.parse("2024-01-01T09:00:00Z");
    /** One worked day (8h), expressed in minutes — mirrors {@code TaskEffortServiceIT}. */
    private static final int ONE_DAY = 8 * 60;
    private static final long THREE_DAYS_MINUTES = 3 * 24 * 60L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /**
     * Registers the container datasource and seeds {@code public} before Spring/Flyway.
     *
     * @param registry the dynamic property registry
     * @throws Exception if seeding the public schema fails
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        PlatformSchemaTestSupport.createPublicSchema(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private BaselineRepository baselineRepository;
    @Autowired private BaselineSnapshotRepository snapshotRepository;
    @Autowired private BaselineService service;

    private long tenantId;
    private long teamId;
    private Project project;

    /** Seeds a fresh tenant/team/application/project before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        teamId = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), tenantId);
        final Application app = applicationRepository.save(new Application(tenantId, teamId, "App", Instant.now()));
        project = projectRepository.save(new Project(app, tenantId, teamId, "P", Instant.now()));
    }

    private Task leaf(final int position, final String name, final Instant start, final Instant finish,
            final int durationMinutes) {
        final Task t = new Task(tenantId, teamId, project.getId(), position, name, NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setStartDate(start);
        t.setFinishDate(finish);
        t.setDurationMinutes(durationMinutes);
        return taskRepository.save(t);
    }

    private Assignment assignment(final Task task, final String resourceRef, final int workMinutes,
            final BigDecimal cost) {
        final Assignment a = new Assignment(tenantId, teamId, task.getId(), resourceRef, new BigDecimal("100"));
        a.setWorkMinutes(workMinutes);
        a.setCostAmount(cost);
        return assignmentRepository.save(a);
    }

    private void updateAssignmentCost(final Task task, final BigDecimal cost) {
        final Assignment a = assignmentRepository.findAllByTaskIdAndTenantIdAndTeamId(task.getId(), tenantId, teamId)
                .get(0);
        a.setCostAmount(cost);
        a.setWorkMinutes(task.getDurationMinutes());
        assignmentRepository.save(a);
    }

    // -------- AC: pose a baseline freezes dates/duration/work/cost/altitude -----------------------

    @Test
    void setBaseline_autoAssignsIndexZero_andFreezesFields() {
        final Task a = leaf(0, "A", T0, T0.plusSeconds(ONE_DAY * 60L), ONE_DAY);
        assignment(a, "alice", ONE_DAY, new BigDecimal("500.00"));

        final BaselineResponse resp = service.setBaseline(tenantId, teamId, project.getId(), null);

        assertThat(resp.baselineIndex()).isEqualTo((short) 0);
        assertThat(resp.taskCount()).isEqualTo(1);
        final BaselineSnapshot snap = snapshotRepository
                .findAllByBaselineIdAndTenantIdAndTeamId(resp.id(), tenantId, teamId).get(0);
        assertThat(snap.getBlStart()).isEqualTo(T0);
        assertThat(snap.getBlFinish()).isEqualTo(T0.plusSeconds(ONE_DAY * 60L));
        assertThat(snap.getBlDurationMinutes()).isEqualTo(ONE_DAY);
        assertThat(snap.getBlWorkMinutes()).isEqualTo(ONE_DAY);
        assertThat(snap.getBlCostAmount()).isEqualByComparingTo("500.00");
        assertThat(snap.getBlTemporalPrecision()).isEqualTo(TemporalPrecision.DAY);
    }

    @Test
    void setBaseline_secondCall_autoAssignsNextFreeIndex() {
        leaf(0, "A", T0, T0, ONE_DAY);

        final BaselineResponse first = service.setBaseline(tenantId, teamId, project.getId(), null);
        final BaselineResponse second = service.setBaseline(tenantId, teamId, project.getId(), null);

        assertThat(first.baselineIndex()).isEqualTo((short) 0);
        assertThat(second.baselineIndex()).isEqualTo((short) 1);
    }

    @Test
    void setBaseline_explicitIndex_overwritesPreviousBaselineAndSnapshot() {
        final Task a = leaf(0, "A", T0, T0, ONE_DAY);
        final BaselineResponse first = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        a.setDurationMinutes(2 * ONE_DAY);
        taskRepository.save(a);
        final BaselineResponse second = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        assertThat(second.baselineIndex()).isEqualTo((short) 0);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(baselineRepository.findById(first.id())).isEmpty();
        final BaselineSnapshot snap = snapshotRepository
                .findAllByBaselineIdAndTenantIdAndTeamId(second.id(), tenantId, teamId).get(0);
        assertThat(snap.getBlDurationMinutes()).isEqualTo(2 * ONE_DAY);
    }

    // -------- Error AC: a 12th baseline is refused, inviting overwrite/delete -----------------------

    @Test
    void setBaseline_elevenSlotsUsed_autoAssign_throwsLimitExceeded() {
        leaf(0, "A", T0, T0, ONE_DAY);
        for (short i = 0; i <= 10; i++) {
            service.setBaseline(tenantId, teamId, project.getId(), i);
        }

        assertThatExceptionOfType(BaselineLimitExceededException.class).isThrownBy(() ->
                service.setBaseline(tenantId, teamId, project.getId(), null));
    }

    @Test
    void setBaseline_elevenSlotsUsed_explicitExistingIndex_stillOverwrites() {
        leaf(0, "A", T0, T0, ONE_DAY);
        for (short i = 0; i <= 10; i++) {
            service.setBaseline(tenantId, teamId, project.getId(), i);
        }

        // An explicit, already-used index is the "écraser" action — never blocked by the limit.
        final BaselineResponse overwritten = service.setBaseline(tenantId, teamId, project.getId(), (short) 3);
        assertThat(overwritten.baselineIndex()).isEqualTo((short) 3);
        assertThat(baselineRepository.findAllByProjectIdAndTenantIdAndTeamId(project.getId(), tenantId, teamId))
                .hasSize(11);
    }

    @Test
    void setBaseline_indexOutOfRange_throwsInvalidBaselineIndex() {
        leaf(0, "A", T0, T0, ONE_DAY);

        assertThatExceptionOfType(InvalidBaselineIndexException.class).isThrownBy(() ->
                service.setBaseline(tenantId, teamId, project.getId(), (short) 11));
    }

    // -------- delete ----------------------------------------------------------------------------

    @Test
    void deleteBaseline_removesBaselineAndCascadesSnapshots() {
        leaf(0, "A", T0, T0, ONE_DAY);
        final BaselineResponse posed = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        service.deleteBaseline(tenantId, teamId, project.getId(), (short) 0);

        assertThat(baselineRepository.findById(posed.id())).isEmpty();
        assertThat(snapshotRepository.findAllByBaselineIdAndTenantIdAndTeamId(posed.id(), tenantId, teamId))
                .isEmpty();
    }

    @Test
    void deleteBaseline_unknownIndex_throwsNotFound() {
        assertThatExceptionOfType(BaselineNotFoundException.class).isThrownBy(() ->
                service.deleteBaseline(tenantId, teamId, project.getId(), (short) 3));
    }

    // -------- list --------------------------------------------------------------------------------

    @Test
    void listBaselines_returnsOrderedByIndexWithTaskCount() {
        leaf(0, "A", T0, T0, ONE_DAY);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 5);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 1);

        final List<BaselineResponse> list = service.listBaselines(tenantId, teamId, project.getId());

        assertThat(list).hasSize(2);
        assertThat(list.get(0).baselineIndex()).isEqualTo((short) 1);
        assertThat(list.get(1).baselineIndex()).isEqualTo((short) 5);
        assertThat(list.get(0).taskCount()).isEqualTo(1);
    }

    // -------- AC: écarts (jours, %, coût) against the current graph ---------------------------------

    @Test
    void variance_lateOverBudgetTask_reportsRetardHausseDepassement() {
        final Task a = leaf(0, "A", T0, T0.plusSeconds(ONE_DAY * 60L), ONE_DAY);
        assignment(a, "alice", ONE_DAY, new BigDecimal("1000.00"));
        final BaselineResponse baseline = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        // Drift: the task slips three days later, its duration doubles, its cost overruns.
        a.setStartDate(T0.plusSeconds(3 * 24 * 3600L));
        a.setFinishDate(T0.plusSeconds(3 * 24 * 3600L + ONE_DAY * 60L));
        a.setDurationMinutes(2 * ONE_DAY);
        taskRepository.save(a);
        updateAssignmentCost(a, new BigDecimal("1500.00"));

        final BaselineVarianceResponse variance =
                service.variance(tenantId, teamId, project.getId(), baseline.baselineIndex());

        assertThat(variance.baselineIndex()).isEqualTo((short) 0);
        assertThat(variance.tasks()).hasSize(1);
        final TaskVarianceResponse row = variance.tasks().get(0);
        assertThat(row.taskName()).isEqualTo("A");
        assertThat(row.startVarianceMinutes()).isEqualTo(THREE_DAYS_MINUTES);
        assertThat(row.startVarianceLabel()).contains("retard");
        assertThat(row.durationVariancePercent()).isEqualByComparingTo("100.00");
        assertThat(row.durationVarianceLabel()).contains("hausse");
        assertThat(row.workVariancePercent()).isEqualByComparingTo("100.00");
        assertThat(row.costVarianceAmount()).isEqualByComparingTo("500.00");
        assertThat(row.costVariancePercent()).isEqualByComparingTo("50.00");
        assertThat(row.costVarianceLabel()).contains("dépassement");
    }

    @Test
    void variance_earlyUnderBudgetTask_reportsAvanceBaisseEconomie() {
        final Instant blStart = T0.plusSeconds(3 * 24 * 3600L);
        final Task a = leaf(0, "A", blStart, blStart.plusSeconds(2L * ONE_DAY * 60L), 2 * ONE_DAY);
        assignment(a, "alice", 2 * ONE_DAY, new BigDecimal("1500.00"));
        final BaselineResponse baseline = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        a.setStartDate(T0);
        a.setFinishDate(T0.plusSeconds(ONE_DAY * 60L));
        a.setDurationMinutes(ONE_DAY);
        taskRepository.save(a);
        updateAssignmentCost(a, new BigDecimal("1000.00"));

        final BaselineVarianceResponse variance =
                service.variance(tenantId, teamId, project.getId(), baseline.baselineIndex());

        final TaskVarianceResponse row = variance.tasks().get(0);
        assertThat(row.startVarianceMinutes()).isEqualTo(-THREE_DAYS_MINUTES);
        assertThat(row.startVarianceLabel()).contains("avance");
        assertThat(row.durationVarianceLabel()).contains("baisse");
        assertThat(row.costVarianceAmount()).isEqualByComparingTo("-500.00");
        assertThat(row.costVarianceLabel()).contains("économie");
    }

    @Test
    void variance_noChange_reportsSansEcart() {
        final Task a = leaf(0, "A", T0, T0.plusSeconds(ONE_DAY * 60L), ONE_DAY);
        assignment(a, "alice", ONE_DAY, new BigDecimal("500.00"));
        final BaselineResponse baseline = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        final BaselineVarianceResponse variance =
                service.variance(tenantId, teamId, project.getId(), baseline.baselineIndex());

        final TaskVarianceResponse row = variance.tasks().get(0);
        assertThat(row.startVarianceMinutes()).isZero();
        assertThat(row.startVarianceLabel()).isEqualTo("Début sans écart");
        assertThat(row.durationVarianceLabel()).isEqualTo("Durée sans écart");
        assertThat(row.costVarianceLabel()).isEqualTo("Coût sans écart");
    }

    @Test
    void variance_temporalPrecisionChanged_flagTrue() {
        final Task a = leaf(0, "A", T0, T0, ONE_DAY);
        final BaselineResponse baseline = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        a.setTemporalPrecision(TemporalPrecision.WEEK);
        taskRepository.save(a);

        final BaselineVarianceResponse variance =
                service.variance(tenantId, teamId, project.getId(), baseline.baselineIndex());

        final TaskVarianceResponse row = variance.tasks().get(0);
        assertThat(row.baselineTemporalPrecision()).isEqualTo(TemporalPrecision.DAY);
        assertThat(row.currentTemporalPrecision()).isEqualTo(TemporalPrecision.WEEK);
        assertThat(row.temporalPrecisionChanged()).isTrue();
    }

    @Test
    void variance_unknownBaselineIndex_throwsNotFound() {
        assertThatExceptionOfType(BaselineNotFoundException.class).isThrownBy(() ->
                service.variance(tenantId, teamId, project.getId(), (short) 4));
    }

    // -------- AC: compare two baselines shows the evolution between references ---------------------

    @Test
    void compare_twoBaselines_showsEvolution() {
        final Task a = leaf(0, "A", T0, T0, ONE_DAY);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        a.setDurationMinutes(2 * ONE_DAY);
        taskRepository.save(a);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 1);

        final BaselineComparisonResponse cmp =
                service.compare(tenantId, teamId, project.getId(), (short) 0, (short) 1);

        assertThat(cmp.fromIndex()).isEqualTo((short) 0);
        assertThat(cmp.toIndex()).isEqualTo((short) 1);
        assertThat(cmp.tasks()).hasSize(1);
        final BaselineComparisonRowResponse row = cmp.tasks().get(0);
        assertThat(row.fromDurationMinutes()).isEqualTo(ONE_DAY);
        assertThat(row.toDurationMinutes()).isEqualTo(2 * ONE_DAY);
        assertThat(row.durationDeltaMinutes()).isEqualTo(ONE_DAY);
        assertThat(row.durationDeltaLabel()).contains("hausse");
    }

    @Test
    void compare_taskAddedBetweenBaselines_onlyInSecondSnapshot() {
        leaf(0, "A", T0, T0, ONE_DAY);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        leaf(1, "B", T0, T0, ONE_DAY);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 1);

        final BaselineComparisonResponse cmp =
                service.compare(tenantId, teamId, project.getId(), (short) 0, (short) 1);

        assertThat(cmp.tasks()).hasSize(2);
        final BaselineComparisonRowResponse newTaskRow = cmp.tasks().stream()
                .filter(r -> "B".equals(r.taskName()))
                .findFirst()
                .orElseThrow();
        assertThat(newTaskRow.fromDurationMinutes()).isNull();
        assertThat(newTaskRow.toDurationMinutes()).isEqualTo(ONE_DAY);
        assertThat(newTaskRow.durationDeltaMinutes()).isNull();
        assertThat(newTaskRow.durationDeltaLabel()).contains("non comparable");
    }

    @Test
    void compare_unknownIndex_throwsNotFound() {
        leaf(0, "A", T0, T0, ONE_DAY);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 0);

        assertThatExceptionOfType(BaselineNotFoundException.class).isThrownBy(() ->
                service.compare(tenantId, teamId, project.getId(), (short) 0, (short) 7));
    }

    // -------- Security AC: cross-tenant/cross-team = 404 non-disclosure -----------------------------

    @Test
    void setBaseline_crossTenant_isNotFound() {
        final long otherTenant = tenantId + 999L;

        assertThatExceptionOfType(BaselineProjectNotFoundException.class).isThrownBy(() ->
                service.setBaseline(otherTenant, teamId, project.getId(), null));
    }

    @Test
    void deleteBaseline_crossTeam_isNotFound() {
        leaf(0, "A", T0, T0, ONE_DAY);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 0);
        final long otherTeam = teamId + 999L;

        assertThatExceptionOfType(BaselineProjectNotFoundException.class).isThrownBy(() ->
                service.deleteBaseline(tenantId, otherTeam, project.getId(), (short) 0));
    }

    @Test
    void variance_crossTeam_isNotFound() {
        leaf(0, "A", T0, T0, ONE_DAY);
        final BaselineResponse baseline = service.setBaseline(tenantId, teamId, project.getId(), (short) 0);
        final long otherTeam = teamId + 999L;

        assertThatExceptionOfType(BaselineProjectNotFoundException.class).isThrownBy(() ->
                service.variance(tenantId, otherTeam, project.getId(), baseline.baselineIndex()));
    }

    @Test
    void compare_crossTenant_isNotFound() {
        leaf(0, "A", T0, T0, ONE_DAY);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 0);
        service.setBaseline(tenantId, teamId, project.getId(), (short) 1);
        final long otherTenant = tenantId + 999L;

        assertThatExceptionOfType(BaselineProjectNotFoundException.class).isThrownBy(() ->
                service.compare(otherTenant, teamId, project.getId(), (short) 0, (short) 1));
    }
}

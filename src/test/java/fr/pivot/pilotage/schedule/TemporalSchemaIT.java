package fr.pivot.pilotage.schedule;

import fr.pivot.pilotage.baseline.Baseline;
import fr.pivot.pilotage.baseline.BaselineRepository;
import fr.pivot.pilotage.baseline.BaselineSnapshot;
import fr.pivot.pilotage.baseline.BaselineSnapshotRepository;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the EN22.1a temporal {@code pilotage} schema (frozen contract §a — the 10
 * tables added on top of EN18.1 plus the temporal columns on {@code project}) against a real
 * PostgreSQL 18 provided by Testcontainers, with this module's Flyway migration applied.
 *
 * <p>Each test maps to an invariant/constraint actually carried by the <em>schema</em>. Rules that
 * belong to the engine/service (EN22.1b/c) — CPM computation of derived columns, the 422 refusal
 * of derived-field writes, global acyclicity, altitude arbitration — are deliberately out of scope
 * here. The {@code public.tenants} table (owned by {@code pivot-core}) is seeded before Flyway via
 * {@link PlatformSchemaTestSupport} so the cross-schema FKs resolve.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TemporalSchemaIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /**
     * Registers the container datasource properties and seeds {@code public} before Spring/Flyway.
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

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CalendarRepository calendarRepository;

    @Autowired
    private PhaseRepository phaseRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskDependencyRepository taskDependencyRepository;

    @Autowired
    private TaskConstraintRepository taskConstraintRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private TaskProgressRepository taskProgressRepository;

    @Autowired
    private BaselineRepository baselineRepository;

    @Autowired
    private BaselineSnapshotRepository baselineSnapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long teamId;

    /** Seeds a fresh tenant and team in {@code public.tenants}/{@code public.teams} before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        teamId = seedTeam(tenantId);
    }

    // ---------- helpers -------------------------------------------------------------------

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTeam(final long owner) throws Exception {
        return PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), owner);
    }

    private Project newProject(final long owner, final long team) {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(owner, team, "App", now));
        return projectRepository.save(new Project(app, owner, team, "Project", now));
    }

    private Task newLeaf(final long owner, final long team, final long projectId, final int position,
            final String name) {
        return taskRepository.save(new Task(owner, team, projectId, position, name,
                NodeKind.LEAF, false, TemporalPrecision.DAY, 0));
    }

    // ---------- AC: the 10 tables + project columns exist ---------------------------------

    /**
     * AC: the migration creates the 10 EN22.1a tables in schema {@code pilotage} (verified via
     * {@code information_schema}).
     */
    @Test
    void ac_les10TablesTemporellesCreees() {
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'pilotage'",
                String.class);
        assertThat(tables).contains(
                "phase", "task", "task_dependency", "task_constraint", "calendar",
                "calendar_exception", "assignment", "task_progress", "baseline", "baseline_snapshot");
    }

    /**
     * AC: {@code pilotage.project} carries the new temporal columns from §a.
     */
    @Test
    void ac_projectPorteLesColonnesTemporelles() {
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'project'",
                String.class);
        assertThat(columns).contains(
                "calendar_id", "scheduling_mode", "status_date", "default_temporal_precision");
    }

    /**
     * AC: the central {@code task} node carries its effective-altitude columns (fuzzy bounds and
     * precise dates coexisting on the same row) plus the derived columns.
     */
    @Test
    void ac_taskPorteAltitudeEffectiveEtDerives() {
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'task'",
                String.class);
        assertThat(columns).contains(
                "temporal_precision", "fuzzy_period_start", "fuzzy_period_end",
                "start_date", "finish_date", "duration_minutes",
                "early_start", "early_finish", "late_start", "late_finish",
                "total_slack_minutes", "free_slack_minutes", "is_critical",
                "wbs_code", "node_kind", "shared_in_roadmap", "recurrence_rule", "revision");
    }

    /**
     * AC (security): every EN22.1a table carries a {@code tenant_id} FK to
     * {@code public.tenants(id)} (verified via {@code pg_catalog}, authoritative for the
     * cross-schema FK — cf. EN18.1 IT).
     */
    @Test
    void ac_securite_chaqueTablePorteTenantIdFk() {
        final List<String> tables = List.of(
                "phase", "task", "task_dependency", "task_constraint", "calendar",
                "calendar_exception", "assignment", "task_progress", "baseline", "baseline_snapshot");
        for (final String table : tables) {
            final Integer fkCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_constraint c
                    JOIN pg_attribute a
                      ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                    WHERE c.contype = 'f'
                      AND c.conrelid = ('pilotage.' || ?)::regclass
                      AND c.confrelid = 'public.tenants'::regclass
                      AND a.attname = 'tenant_id'
                      AND array_length(c.conkey, 1) = 1
                    """, Integer.class, table);
            assertThat(fkCount).as("tenant_id FK on pilotage.%s", table).isEqualTo(1);
        }
    }

    /**
     * AC (security, team_id retrofit): every EN22.1a table also carries a {@code team_id} FK to
     * {@code public.teams(id)}, mirroring the {@code tenant_id} FK above.
     */
    @Test
    void ac_securite_chaqueTablePorteTeamIdFk() {
        final List<String> tables = List.of(
                "phase", "task", "task_dependency", "task_constraint", "calendar",
                "calendar_exception", "assignment", "task_progress", "baseline", "baseline_snapshot");
        for (final String table : tables) {
            final Integer fkCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_constraint c
                    JOIN pg_attribute a
                      ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                    WHERE c.contype = 'f'
                      AND c.conrelid = ('pilotage.' || ?)::regclass
                      AND c.confrelid = 'public.teams'::regclass
                      AND a.attname = 'team_id'
                      AND array_length(c.conkey, 1) = 1
                    """, Integer.class, table);
            assertThat(fkCount).as("team_id FK on pilotage.%s", table).isEqualTo(1);
        }
    }

    // ---------- DDL constraints ------------------------------------------------------------

    /**
     * AC (error case): the {@code UNIQUE(predecessor, successor, link_type)} on
     * {@code task_dependency} rejects a duplicate edge.
     */
    @Test
    void ac_erreur_dependanceDoublonRejetee() {
        final Project project = newProject(tenantId, teamId);
        final Task t1 = newLeaf(tenantId, teamId, project.getId(), 0, "T1");
        final Task t2 = newLeaf(tenantId, teamId, project.getId(), 1, "T2");
        taskDependencyRepository.saveAndFlush(
                new TaskDependency(tenantId, teamId, t1.getId(), t2.getId(), DependencyLinkType.FS, 0));

        assertThatThrownBy(() -> taskDependencyRepository.saveAndFlush(
                new TaskDependency(tenantId, teamId, t1.getId(), t2.getId(), DependencyLinkType.FS, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * AC (error case): the {@code CHECK predecessor <> successor} rejects a self-loop.
     */
    @Test
    void ac_erreur_dependanceSurElleMemeRejetee() {
        final Project project = newProject(tenantId, teamId);
        final Task t1 = newLeaf(tenantId, teamId, project.getId(), 0, "T1");

        assertThatThrownBy(() -> taskDependencyRepository.saveAndFlush(
                new TaskDependency(tenantId, teamId, t1.getId(), t1.getId(), DependencyLinkType.FS, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * AC (error case): the {@code CHECK baseline_index BETWEEN 0 AND 10} rejects an out-of-range
     * index.
     */
    @Test
    void ac_erreur_baselineIndexHorsBorneRejete() {
        final Project project = newProject(tenantId, teamId);

        assertThatThrownBy(() -> baselineRepository.saveAndFlush(
                new Baseline(tenantId, teamId, project.getId(), (short) 11, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * AC (error case): the {@code UNIQUE task_id} on {@code task_constraint} enforces 0..1 per
     * task.
     */
    @Test
    void ac_erreur_contrainteUniqueParTache() {
        final Project project = newProject(tenantId, teamId);
        final Task t1 = newLeaf(tenantId, teamId, project.getId(), 0, "T1");
        taskConstraintRepository.saveAndFlush(
                new TaskConstraint(tenantId, teamId, t1.getId(), ConstraintType.ASAP, null, null));

        assertThatThrownBy(() -> taskConstraintRepository.saveAndFlush(
                new TaskConstraint(tenantId, teamId, t1.getId(), ConstraintType.ALAP, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * AC (error case): the {@code UNIQUE task_id} on {@code task_progress} enforces 1:1 with a
     * task.
     */
    @Test
    void ac_erreur_progressionUniqueParTache() {
        final Project project = newProject(tenantId, teamId);
        final Task t1 = newLeaf(tenantId, teamId, project.getId(), 0, "T1");
        taskProgressRepository.saveAndFlush(new TaskProgress(tenantId, teamId, t1.getId(), BigDecimal.ZERO));

        assertThatThrownBy(() -> taskProgressRepository.saveAndFlush(
                new TaskProgress(tenantId, teamId, t1.getId(), new BigDecimal("50.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * AC (security / error case): the cross-schema {@code tenant_id} FK rejects an unknown tenant
     * (SQLState 23503) — a task can never smuggle in a tenant absent from {@code public.tenants}.
     */
    @Test
    void ac_erreur_fkTenantInconnu() {
        final Project project = newProject(tenantId, teamId);
        final long unknownTenantId = 888_888_888L;

        // team_id is bound to the (valid, seeded) teamId so the failure is isolated to the
        // unknown tenant_id under test, not a spurious NOT NULL/FK violation on team_id.
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO pilotage.task "
                                + "(tenant_id, team_id, project_id, position, name, node_kind, "
                                + "shared_in_roadmap, temporal_precision, revision) "
                                + "VALUES (?, ?, ?, 0, 'X', 'LEAF', false, 'DAY', 0)",
                        unknownTenantId, teamId, project.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- shared milestone -----------------------------------------------------------

    /**
     * AC (shared milestone): a {@code MILESTONE} task with {@code shared_in_roadmap=true} and
     * precise dates persists and is reloaded via the repository with the mapping intact — the
     * single record that satisfies both the macro and detail views (one object, two renders).
     */
    @Test
    @Transactional
    void ac_jalonPartagePersisteEtRelu() {
        final Project project = newProject(tenantId, teamId);
        final Instant now = Instant.now();
        final Task milestone = new Task(tenantId, teamId, project.getId(), 0, "Jalon",
                NodeKind.MILESTONE, true, TemporalPrecision.DAY, 0);
        milestone.setStartDate(now);
        milestone.setFinishDate(now);
        milestone.setDurationMinutes(0);
        final Task saved = taskRepository.saveAndFlush(milestone);

        final Task reloaded = taskRepository.findByIdAndTenantId(saved.getId(), tenantId).orElseThrow();
        assertThat(reloaded.getNodeKind()).isEqualTo(NodeKind.MILESTONE);
        assertThat(reloaded.getSharedInRoadmap()).isTrue();
        assertThat(reloaded.getStartDate()).isEqualTo(now);
        assertThat(reloaded.getFinishDate()).isEqualTo(now);
        assertThat(reloaded.getDurationMinutes()).isZero();
        // Derived columns are never computed in EN22.1a — they stay null at rest.
        assertThat(reloaded.getWbsCode()).isNull();
        assertThat(reloaded.getEarlyStart()).isNull();
        assertThat(reloaded.getCritical()).isNull();
    }

    /**
     * AC: the effective altitude coexists on a single row — a {@code QUARTER} task carries fuzzy
     * bounds while precise dates remain available on the same record (no separate view table).
     */
    @Test
    @Transactional
    void ac_altitudeEffectiveCoexisteSurLaMemeLigne() {
        final Project project = newProject(tenantId, teamId);
        final java.time.LocalDate qStart = java.time.LocalDate.of(2026, 1, 1);
        final java.time.LocalDate qEnd = java.time.LocalDate.of(2026, 3, 31);
        final Task task = new Task(tenantId, teamId, project.getId(), 0, "Initiative",
                NodeKind.SUMMARY, true, TemporalPrecision.QUARTER, 0);
        task.setFuzzyPeriodStart(qStart);
        task.setFuzzyPeriodEnd(qEnd);
        final Task saved = taskRepository.saveAndFlush(task);

        final Task reloaded = taskRepository.findByIdAndTenantId(saved.getId(), tenantId).orElseThrow();
        assertThat(reloaded.getTemporalPrecision()).isEqualTo(TemporalPrecision.QUARTER);
        assertThat(reloaded.getFuzzyPeriodStart()).isEqualTo(qStart);
        assertThat(reloaded.getFuzzyPeriodEnd()).isEqualTo(qEnd);
        // Precise Gantt dates are available on the same row (here left null, not a separate table).
        assertThat(reloaded.getStartDate()).isNull();
    }

    // ---------- mini graph -----------------------------------------------------------------

    /**
     * AC: a mini temporal graph (project -&gt; calendar -&gt; phase -&gt; task -&gt; dependency
     * -&gt; assignment -&gt; progress -&gt; baseline + snapshot) persists and is readable back via
     * the repositories, in a single tenant.
     */
    @Test
    @Transactional
    void ac_miniGraphePersisteViaRepositories() {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(tenantId, teamId, "App", now));
        final Project project = projectRepository.save(new Project(app, tenantId, teamId, "P", now));

        final Calendar calendar = calendarRepository.save(new Calendar(
                tenantId, teamId, project.getId(), CalendarScope.PROJECT, "Std", (short) 62, "{}"));
        project.setCalendar(calendar);
        projectRepository.save(project);

        final Phase phase = phaseRepository.save(
                new Phase(tenantId, teamId, project.getId(), null, "Build", 0));

        final Task summary = newLeaf(tenantId, teamId, project.getId(), 0, "Summary");
        final Task leafA = new Task(tenantId, teamId, project.getId(), 1, "A",
                NodeKind.LEAF, false, TemporalPrecision.DAY, 0);
        leafA.setPhaseId(phase.getId());
        leafA.setParentTaskId(summary.getId());
        leafA.setDurationMinutes(480);
        final Task savedA = taskRepository.save(leafA);
        final Task leafB = newLeaf(tenantId, teamId, project.getId(), 2, "B");

        taskDependencyRepository.save(
                new TaskDependency(tenantId, teamId, savedA.getId(), leafB.getId(), DependencyLinkType.FS, 0));
        taskConstraintRepository.save(
                new TaskConstraint(tenantId, teamId, savedA.getId(), ConstraintType.SNET, now, null));
        assignmentRepository.save(
                new Assignment(tenantId, teamId, savedA.getId(), "user:42", new BigDecimal("100.00")));
        taskProgressRepository.save(
                new TaskProgress(tenantId, teamId, savedA.getId(), new BigDecimal("25.00")));

        final Baseline baseline = baselineRepository.save(
                new Baseline(tenantId, teamId, project.getId(), (short) 0, now));
        baselineSnapshotRepository.save(
                new BaselineSnapshot(tenantId, teamId, baseline.getId(), savedA.getId()));

        // Read the graph back via tenant-scoped repositories.
        assertThat(calendarRepository.findAllByTenantId(tenantId)).hasSize(1);
        assertThat(phaseRepository.findAllByProjectIdAndTenantId(project.getId(), tenantId)).hasSize(1);
        assertThat(taskRepository.findAllByProjectIdAndTenantId(project.getId(), tenantId)).hasSize(3);
        assertThat(taskDependencyRepository
                .findAllByPredecessorTaskIdAndTenantId(savedA.getId(), tenantId)).hasSize(1);
        assertThat(taskConstraintRepository.findByTaskIdAndTenantId(savedA.getId(), tenantId)).isPresent();
        assertThat(assignmentRepository.findAllByTaskIdAndTenantId(savedA.getId(), tenantId)).hasSize(1);
        assertThat(taskProgressRepository.findByTaskIdAndTenantId(savedA.getId(), tenantId)).isPresent();
        assertThat(baselineRepository.findAllByProjectIdAndTenantId(project.getId(), tenantId)).hasSize(1);
        assertThat(baselineSnapshotRepository
                .findAllByBaselineIdAndTenantId(baseline.getId(), tenantId)).hasSize(1);
    }

    // ---------- multi-tenant isolation -----------------------------------------------------

    /**
     * AC (security): two tenants T1/T2 are isolated — a tenant-scoped repository read for T1 never
     * returns rows belonging to T2, across every EN22.1a table.
     */
    @Test
    void ac_securite_isolationMultiTenant() throws Exception {
        final long tenantT2 = seedTenant();
        final long teamT2 = seedTeam(tenantT2);

        final Project projectT1 = newProject(tenantId, teamId);
        final Task taskT1 = newLeaf(tenantId, teamId, projectT1.getId(), 0, "T1-task");
        calendarRepository.save(new Calendar(
                tenantId, teamId, projectT1.getId(), CalendarScope.PROJECT, "C1", (short) 62, "{}"));
        assignmentRepository.save(
                new Assignment(tenantId, teamId, taskT1.getId(), "user:1", new BigDecimal("100.00")));

        final Project projectT2 = newProject(tenantT2, teamT2);
        final Task taskT2 = newLeaf(tenantT2, teamT2, projectT2.getId(), 0, "T2-task");
        calendarRepository.save(new Calendar(
                tenantT2, teamT2, projectT2.getId(), CalendarScope.PROJECT, "C2", (short) 62, "{}"));
        assignmentRepository.save(
                new Assignment(tenantT2, teamT2, taskT2.getId(), "user:2", new BigDecimal("100.00")));

        // No T2 data leaks into T1's scope.
        assertThat(taskRepository.findAllByTenantId(tenantId))
                .extracting(Task::getTenantId).containsOnly(tenantId);
        assertThat(calendarRepository.findAllByTenantId(tenantId))
                .extracting(Calendar::getTenantId).containsOnly(tenantId);
        assertThat(assignmentRepository.findAllByTenantId(tenantId))
                .extracting(Assignment::getTenantId).containsOnly(tenantId);
        assertThat(taskRepository.findByIdAndTenantId(taskT2.getId(), tenantId)).isEmpty();
    }
}

package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarRepository;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.DependencyLinkType;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
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

import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link DependencyService} (US22.4.3) against a real PostgreSQL 18
 * (Testcontainers), this module's Flyway migration applied. Verifies — through the real engine — that
 * the four link types (FS/SS/FF/SF) shift the successor correctly, that a signed lag/lead is applied,
 * that a duplicate and a self-link are rejected, that a cycle propagates {@code SCHEDULE_CYCLE} with
 * no partial state, that deletion works, and that cross-tenant access is a non-disclosing 404.
 *
 * <p>Calendar is Mon-Fri 09:00-17:00 (8 worked hours/day); the anchor project start is a Monday in
 * the past (no {@code now()}), mirroring {@code SchedulingServiceIT}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DependencyServiceIT {

    private static final String WT = "{\"ranges\":[[\"09:00\",\"17:00\"]]}";
    private static final Instant MON_0900 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
    private static final Instant MON_1700 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(17).toInstant();
    private static final Instant TUE_0900 =
            LocalDate.of(2024, 1, 2).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
    /** One working day = 8h = 480 worked minutes. */
    private static final int ONE_DAY = 480;

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
    @Autowired private CalendarRepository calendarRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskDependencyRepository dependencyRepository;
    @Autowired private DependencyService dependencyService;

    private long tenantId;
    private long teamId;
    private long projectId;

    /** Seeds a fresh tenant/team/project (with a Mon-Fri 09:00-17:00 calendar) before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
        teamId = seedTeam(tenantId);
        projectId = newProjectWithCalendar(tenantId, teamId).getId();
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTeam(final long owner) throws Exception {
        return PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), owner);
    }

    private Project newProjectWithCalendar(final long owner, final long team) {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(owner, team, "App", now));
        final Project project = projectRepository.save(new Project(app, owner, team, "P", now));
        final Calendar cal = calendarRepository.save(new Calendar(
                owner, team, project.getId(), CalendarScope.PROJECT, "Std", (short) 0b0011111, WT));
        project.setCalendar(cal);
        project.setStatusDate(LocalDate.of(2024, 1, 1));
        return projectRepository.save(project);
    }

    private Task leaf(final int position, final String name, final int durationMinutes) {
        final Task t = new Task(tenantId, teamId, projectId, position, name, NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setDurationMinutes(durationMinutes);
        t.setStartDate(MON_0900); // anchor the project start deterministically
        return taskRepository.save(t);
    }

    private Task reload(final long taskId) {
        return taskRepository.findByIdAndTenantId(taskId, tenantId).orElseThrow();
    }

    // -------- AC: FS link — successor starts after predecessor finishes --------------------------

    @Test
    void create_fs_successorStartsAfterPredecessorFinish() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);

        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0));

        // A: Mon 09:00→17:00 ; B (FS) starts next working slot: Tue 09:00.
        assertThat(reload(a.getId()).getEarlyFinish()).isEqualTo(MON_1700);
        assertThat(reload(b.getId()).getEarlyStart()).isEqualTo(TUE_0900);
    }

    // -------- AC: SS link — successor starts when predecessor starts -----------------------------

    @Test
    void create_ss_successorStartsWithPredecessor() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);

        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.SS, 0));

        // SS + 0 lag: B starts exactly when A starts (Mon 09:00).
        assertThat(reload(b.getId()).getEarlyStart()).isEqualTo(MON_0900);
    }

    // -------- AC: FF link — successor finishes when predecessor finishes -------------------------

    @Test
    void create_ff_successorFinishesWithPredecessor() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);

        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FF, 0));

        // FF + 0 lag: B finishes when A finishes (Mon 17:00), hence B also starts Mon 09:00.
        assertThat(reload(b.getId()).getEarlyFinish()).isEqualTo(MON_1700);
        assertThat(reload(b.getId()).getEarlyStart()).isEqualTo(MON_0900);
    }

    // -------- AC: SF link — successor finishes when predecessor starts ---------------------------

    @Test
    void create_sf_successorFinishesWhenPredecessorStarts() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);

        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.SF, 0));

        // SF + 0 lag: B must finish no earlier than A starts (Mon 09:00). B's earliest finish is at
        // least its own duration after the anchored start, so it finishes Mon 17:00 (start Mon 09:00).
        assertThat(reload(b.getId()).getEarlyFinish()).isEqualTo(MON_1700);
    }

    // -------- AC: signed lag (retard) — FS + one working day of lag pushes the successor ---------

    @Test
    void create_fsWithPositiveLag_pushesSuccessorByLag() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);

        // FS + 480 worked minutes (one working day) of lag: A finishes Mon 17:00, +1 working day ⇒
        // B starts Wed 09:00 (Tue is consumed by the lag).
        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, ONE_DAY));

        final Instant wed0900 = LocalDate.of(2024, 1, 3).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();
        assertThat(reload(b.getId()).getEarlyStart()).isEqualTo(wed0900);
    }

    // -------- AC: signed lag (avance/lead) — SS with negative lag is clamped at project start ----

    @Test
    void create_ssWithNegativeLag_leadsSuccessorButNotBeforeProjectStart() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);

        // SS - 480 (one working day of lead): B would start a day before A, but A is at the project
        // start floor (Mon 09:00), so B is clamped to Mon 09:00 — the lead is honoured up to the floor.
        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.SS, -ONE_DAY));

        assertThat(reload(b.getId()).getEarlyStart()).isEqualTo(MON_0900);
    }

    // -------- Error AC: self-dependency rejected, nothing persisted ------------------------------

    @Test
    void create_selfDependency_rejected() {
        final Task a = leaf(0, "A", ONE_DAY);

        assertThatExceptionOfType(InvalidDependencyException.class).isThrownBy(() ->
                dependencyService.create(tenantId, teamId, projectId,
                        new CreateDependencyRequest(a.getId(), a.getId(), DependencyLinkType.FS, 0)));

        assertThat(dependencyRepository.findAllByTenantId(tenantId)).isEmpty();
    }

    // -------- Error AC: duplicate link rejected -------------------------------------------------

    @Test
    void create_duplicate_rejected() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);
        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0));

        assertThatExceptionOfType(DuplicateDependencyException.class).isThrownBy(() ->
                dependencyService.create(tenantId, teamId, projectId,
                        new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0)));

        assertThat(dependencyRepository.findAllByTenantId(tenantId)).hasSize(1);
    }

    // -------- Error AC (atomicity): a cycle → SCHEDULE_CYCLE, the second edge is NOT persisted ----

    @Test
    void create_cycle_rejectedAtomically_noPartialState() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);
        // A → B is fine.
        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0));

        // B → A closes a cycle: the engine rejects with SCHEDULE_CYCLE; the tentative edge rolls back.
        assertThatExceptionOfType(DependencyCycleException.class).isThrownBy(() ->
                dependencyService.create(tenantId, teamId, projectId,
                        new CreateDependencyRequest(b.getId(), a.getId(), DependencyLinkType.FS, 0)));

        // Only the first (acyclic) edge survives — no partial state from the rejected create.
        assertThat(dependencyRepository.findAllByTenantId(tenantId)).hasSize(1);
    }

    // -------- AC: update retypes/relags and recomputes -------------------------------------------

    @Test
    void update_retypeToSs_recomputes() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);
        final DependencyResponse created = dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0));
        assertThat(reload(b.getId()).getEarlyStart()).isEqualTo(TUE_0900); // FS

        dependencyService.update(tenantId, teamId, projectId, created.dependencyId(),
                new UpdateDependencyRequest(DependencyLinkType.SS, 0));

        // Now SS: B starts with A (Mon 09:00).
        assertThat(reload(b.getId()).getEarlyStart()).isEqualTo(MON_0900);
    }

    // -------- AC: delete removes the link and recomputes ----------------------------------------

    @Test
    void delete_removesLink() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);
        final DependencyResponse created = dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0));

        dependencyService.delete(tenantId, teamId, projectId, created.dependencyId());

        assertThat(dependencyRepository.findAllByTenantId(tenantId)).isEmpty();
    }

    // -------- AC: list returns the project's links ----------------------------------------------

    @Test
    void list_returnsProjectDependencies() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);
        final Task c = leaf(2, "C", ONE_DAY);
        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0));
        dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(b.getId(), c.getId(), DependencyLinkType.SS, 60));

        final List<DependencyResponse> deps = dependencyService.list(tenantId, teamId, projectId);

        assertThat(deps).hasSize(2);
        assertThat(deps).anyMatch(d -> d.linkType() == DependencyLinkType.SS && d.lagMinutes() == 60);
    }

    // -------- Security AC: cross-tenant create → 404 non-disclosure, nothing persisted -----------

    @Test
    void create_crossTenant_returns404() throws Exception {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);
        final long otherTenant = seedTenant();

        // Another tenant cannot see this project — the project lookup fails first, non-disclosing 404.
        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                dependencyService.create(otherTenant, teamId, projectId,
                        new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, 0)));

        assertThat(dependencyRepository.findAllByTenantId(tenantId)).isEmpty();
    }

    // -------- Security AC: an endpoint task on another project → 404 -----------------------------

    @Test
    void create_endpointOnAnotherProject_returns404() {
        final Task a = leaf(0, "A", ONE_DAY);
        // A task belonging to a different project of the same tenant/team.
        final Project other = newProjectWithCalendar(tenantId, teamId);
        final Task foreign = new Task(tenantId, teamId, other.getId(), 0, "X", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        foreign.setDurationMinutes(ONE_DAY);
        final Task savedForeign = taskRepository.save(foreign);

        assertThatExceptionOfType(DependencyNotFoundException.class).isThrownBy(() ->
                dependencyService.create(tenantId, teamId, projectId,
                        new CreateDependencyRequest(a.getId(), savedForeign.getId(), DependencyLinkType.FS, 0)));

        assertThat(dependencyRepository.findAllByTenantId(tenantId)).isEmpty();
    }

    // -------- retain a direct-entity path so the repository duplicate query is exercised too -----

    @Test
    void repository_persistsSignedLag() {
        final Task a = leaf(0, "A", ONE_DAY);
        final Task b = leaf(1, "B", ONE_DAY);
        final DependencyResponse d = dependencyService.create(tenantId, teamId, projectId,
                new CreateDependencyRequest(a.getId(), b.getId(), DependencyLinkType.FS, -120));

        final TaskDependency reloaded = dependencyRepository.findByIdAndTenantIdAndTeamId(
                d.dependencyId(), tenantId, teamId).orElseThrow();
        assertThat(reloaded.getLagMinutes()).isEqualTo(-120);
    }
}

package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressHistory;
import fr.pivot.pilotage.schedule.TaskProgressHistoryRepository;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
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
 * Integration tests for {@link TaskProgressService} (US22.4.8 — suivi d'avancement) against a real
 * PostgreSQL 18 (Testcontainers), this module's Flyway migration applied. One test per behavioural
 * AC: percent-complete save refreshing the bar and the actual/remaining work (MS-Project relation),
 * the audit trail (author, date), the {@code [0, 100]}/actual-dates error ACs, the summary
 * derived-field refusal and multi-tenant/team isolation (404 non-disclosure).
 * {@code public.tenants}/{@code public.teams} are seeded before Flyway via
 * {@link PlatformSchemaTestSupport}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TaskProgressServiceIT {

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
    @Autowired private TaskProgressRepository progressRepository;
    @Autowired private TaskProgressHistoryRepository historyRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private TaskProgressService service;

    private long tenantId;
    private long teamId;
    private long projectId;

    /** Seeds a fresh tenant/team/application/project before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        teamId = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), tenantId);
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(tenantId, teamId, "App", now));
        projectId = projectRepository.save(new Project(app, tenantId, teamId, "P", now)).getId();
    }

    private Task leaf(final String name, final int durationMinutes) {
        final Task t = new Task(tenantId, teamId, projectId, 0, name, NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setDurationMinutes(durationMinutes);
        return taskRepository.save(t);
    }

    private Task summary(final String name) {
        return taskRepository.save(new Task(tenantId, teamId, projectId, 0, name, NodeKind.SUMMARY, false,
                TemporalPrecision.DAY, 0));
    }

    private Assignment assignment(final long taskId, final String resourceRef, final int workMinutes) {
        final Assignment a = new Assignment(tenantId, teamId, taskId, resourceRef, new BigDecimal("100"));
        a.setWorkMinutes(workMinutes);
        return assignmentRepository.save(a);
    }

    // -------- AC: percent complete save refreshes the bar and the actual/remaining work ------------

    @Test
    void setProgress_refreshesBarAndActualRemainingWork() {
        final Task task = leaf("A", 480);
        assignment(task.getId(), "alice", 480);

        final TaskProgressResponse resp = service.setProgress(tenantId, teamId, projectId, task.getId(),
                new UpdateTaskProgressRequest(new BigDecimal("25"), null, null, null, null, "user:pm"));

        assertThat(resp.percentComplete()).isEqualByComparingTo("25");
        assertThat(resp.progressLabel()).isEqualTo("25%");
        assertThat(resp.totalWorkMinutes()).isEqualTo(480);
        assertThat(resp.actualWorkMinutes()).isEqualTo(120); // 25% of 480
        assertThat(resp.remainingWorkMinutes()).isEqualTo(360); // 480 - 120, MS-Project relation

        final Assignment saved = assignmentRepository
                .findAllByTaskIdAndTenantIdAndTeamId(task.getId(), tenantId, teamId).get(0);
        assertThat(saved.getActualWorkMinutes()).isEqualTo(120);
        assertThat(saved.getRemainingWorkMinutes()).isEqualTo(360);
    }

    // -------- AC: a task without an assignment has no total/actual/remaining work ------------------

    @Test
    void setProgress_withoutAssignment_hasNullWorkTotals() {
        final Task task = leaf("A", 480);

        final TaskProgressResponse resp = service.setProgress(tenantId, teamId, projectId, task.getId(),
                new UpdateTaskProgressRequest(new BigDecimal("50"), null, null, null, null, "user:pm"));

        assertThat(resp.totalWorkMinutes()).isNull();
        assertThat(resp.actualWorkMinutes()).isNull();
        assertThat(resp.remainingWorkMinutes()).isNull();
    }

    // -------- AC re-save: 100% completes the work, no negative remaining ----------------------------

    @Test
    void setProgress_hundredPercent_zeroesRemainingWork() {
        final Task task = leaf("A", 480);
        assignment(task.getId(), "alice", 480);

        final TaskProgressResponse resp = service.setProgress(tenantId, teamId, projectId, task.getId(),
                new UpdateTaskProgressRequest(new BigDecimal("100"), null, null, null, null, "user:pm"));

        assertThat(resp.actualWorkMinutes()).isEqualTo(480);
        assertThat(resp.remainingWorkMinutes()).isZero();
    }

    // -------- Security AC: the audit trail traces author and date -----------------------------------

    @Test
    void setProgress_appendsAuditTrailEntry_withAuthorAndDate() {
        final Task task = leaf("A", 480);

        service.setProgress(tenantId, teamId, projectId, task.getId(),
                new UpdateTaskProgressRequest(new BigDecimal("30"), null, null, null, null, "user:bob"));
        service.setProgress(tenantId, teamId, projectId, task.getId(),
                new UpdateTaskProgressRequest(new BigDecimal("60"), null, null, null, null, "user:carol"));

        final List<TaskProgressHistory> history = historyRepository
                .findAllByTaskIdAndTenantIdAndTeamIdOrderByRecordedAtDesc(task.getId(), tenantId, teamId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getActorRef()).isEqualTo("user:carol");
        assertThat(history.get(0).getPercentComplete()).isEqualByComparingTo("60");
        assertThat(history.get(0).getRecordedAt()).isNotNull();
        assertThat(history.get(1).getActorRef()).isEqualTo("user:bob");

        // the current-state row holds only the latest value (1:1 task)
        final TaskProgress current = progressRepository.findByTaskIdAndTenantIdAndTeamId(
                task.getId(), tenantId, teamId).orElseThrow();
        assertThat(current.getPercentComplete()).isEqualByComparingTo("60");
    }

    // -------- Error AC: percent complete out of [0, 100] is rejected, nothing persisted -------------

    @Test
    void setProgress_percentOutOfRange_rejectedWithoutPersisting() {
        final Task task = leaf("A", 480);

        assertThatExceptionOfType(InvalidTaskProgressException.class).isThrownBy(() ->
                service.setProgress(tenantId, teamId, projectId, task.getId(),
                        new UpdateTaskProgressRequest(new BigDecimal("101"), null, null, null, null, "user:pm")));

        assertThat(progressRepository.findByTaskIdAndTenantIdAndTeamId(task.getId(), tenantId, teamId)).isEmpty();
        assertThat(historyRepository
                .findAllByTaskIdAndTenantIdAndTeamIdOrderByRecordedAtDesc(task.getId(), tenantId, teamId)).isEmpty();
    }

    @Test
    void setProgress_negativePercent_rejected() {
        final Task task = leaf("A", 480);

        assertThatExceptionOfType(InvalidTaskProgressException.class).isThrownBy(() ->
                service.setProgress(tenantId, teamId, projectId, task.getId(),
                        new UpdateTaskProgressRequest(new BigDecimal("-1"), null, null, null, null, "user:pm")));
    }

    // -------- Error AC: an actual finish before the actual start is rejected ------------------------

    @Test
    void setProgress_actualFinishBeforeActualStart_rejected() {
        final Task task = leaf("A", 480);
        final Instant start = Instant.parse("2026-01-10T00:00:00Z");
        final Instant finish = Instant.parse("2026-01-01T00:00:00Z");

        assertThatExceptionOfType(InvalidTaskProgressException.class).isThrownBy(() ->
                service.setProgress(tenantId, teamId, projectId, task.getId(),
                        new UpdateTaskProgressRequest(new BigDecimal("50"), null, start, finish, null, "user:pm")));
    }

    // -------- Error AC: a summary task's percent is derived (aggregated), refused -------------------

    @Test
    void setProgress_summaryTask_rejectedAsDerivedField() {
        final Task summary = summary("Phase");

        assertThatExceptionOfType(DerivedFieldNotEditableException.class).isThrownBy(() ->
                service.setProgress(tenantId, teamId, projectId, summary.getId(),
                        new UpdateTaskProgressRequest(new BigDecimal("50"), null, null, null, null, "user:pm")));
    }

    // -------- Security AC: a task of another tenant is a 404-equivalent (non-disclosure) ------------

    @Test
    void setProgress_crossTenant_isNotFound() {
        final Task task = leaf("A", 480);
        final long otherTenant = tenantId + 999L;

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                service.setProgress(otherTenant, teamId, projectId, task.getId(),
                        new UpdateTaskProgressRequest(new BigDecimal("50"), null, null, null, null, "user:pm")));
    }

    @Test
    void setProgress_crossTeam_isNotFound() {
        final Task task = leaf("A", 480);
        final long otherTeam = teamId + 999L;

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                service.setProgress(tenantId, otherTeam, projectId, task.getId(),
                        new UpdateTaskProgressRequest(new BigDecimal("50"), null, null, null, null, "user:pm")));
    }
}

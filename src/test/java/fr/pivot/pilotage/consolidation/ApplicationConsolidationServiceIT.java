package fr.pivot.pilotage.consolidation;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link ApplicationConsolidationService} (EN18.9) against a real PostgreSQL
 * 18 (Testcontainers). Proves: the application-level roll-up of an application with N projects
 * (count, projects per derived status, temporal window, unified {@code shared_in_roadmap}
 * milestones) computed purely through tenant-scoped {@code pilotage} repositories — no inter-module
 * FK; the deterministic {@code project → application} resolution; multi-tenant isolation (a foreign
 * tenant's application is invisible → 404 equivalent); and the cross-module SPI surfacing a
 * test-only contributor's aggregate. Timestamps are anchored in the past — never {@code now()}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ApplicationConsolidationServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /**
     * Test-only cross-module contributor proving the bus extension point: it returns a fixed
     * per-application aggregate, exactly as a real budget/risk module would over the PIVOT bus.
     */
    @TestConfiguration
    static class FakeContributorConfig {
        @Bean
        ApplicationDataContributor fakeBudgetContributor() {
            return (tenantId, applicationId) -> Optional.of(
                    new ApplicationAggregateContribution("budget",
                            Map.of("applicationId", applicationId, "consumedRatio", 0.5)));
        }
    }

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
    @Autowired private ApplicationConsolidationService consolidationService;
    @Autowired private ProjectApplicationResolver resolver;

    private long tenantId;

    private static final Instant ANCHOR = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant MON_0900 =
            LocalDate.of(2024, 2, 5).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();

    /** Seeds a fresh tenant before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Application newApplication(final long owner, final String name) {
        return applicationRepository.save(new Application(owner, name, ANCHOR));
    }

    private Project newProject(final long owner, final Application app, final String name) {
        return projectRepository.save(new Project(app, owner, name, ANCHOR));
    }

    private Task leaf(final long owner, final long projectId, final Instant start) {
        final Task t = new Task(owner, projectId, 0, "leaf", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        t.setStartDate(start);
        return taskRepository.save(t);
    }

    private Task sharedMilestone(final long owner, final long projectId, final String name,
            final LocalDate fuzzyStart, final LocalDate fuzzyEnd) {
        final Task t = new Task(owner, projectId, 0, name, NodeKind.MILESTONE, true,
                TemporalPrecision.DAY, 0);
        t.setFuzzyPeriodStart(fuzzyStart);
        t.setFuzzyPeriodEnd(fuzzyEnd);
        return taskRepository.save(t);
    }

    // -------- AC: application with N projects → correct pilotage aggregate ----------------------

    @Test
    void consolidate_nProjects_aggregatesCountWindowStatusAndUnifiedMilestones() {
        final Application app = newApplication(tenantId, "Billing");
        final Project v1 = newProject(tenantId, app, "v1");
        final Project v2 = newProject(tenantId, app, "v2");
        final Project v3 = newProject(tenantId, app, "v3");

        leaf(tenantId, v1.getId(), MON_0900);
        final Task m1 = sharedMilestone(tenantId, v1.getId(), "Go-live v1",
                LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 31));
        final Task m2 = sharedMilestone(tenantId, v2.getId(), "Go-live v2",
                LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 30));
        // v2 also has a non-shared leaf without precise dates → v2 is PLANNED overall
        taskRepository.save(new Task(tenantId, v2.getId(), 1, "wip", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0));
        // v3: no task → EMPTY

        final ApplicationConsolidation c = consolidationService.consolidate(tenantId, app.getId());

        assertThat(c.applicationId()).isEqualTo(app.getId());
        assertThat(c.applicationName()).isEqualTo("Billing");
        assertThat(c.projectCount()).isEqualTo(3);
        assertThat(c.projectsByStatus()).isEqualTo(Map.of(
                ProjectPlanningStatus.SCHEDULED, 1,
                ProjectPlanningStatus.PLANNED, 1,
                ProjectPlanningStatus.EMPTY, 1));
        assertThat(c.windowStart()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(c.windowFinish()).isEqualTo(LocalDate.of(2024, 4, 30));
        assertThat(c.strategicMilestones()).extracting(ApplicationMilestone::nodeId)
                .containsExactly(m1.getId(), m2.getId());
        assertThat(c.strategicMilestones()).extracting(ApplicationMilestone::projectId)
                .containsExactly(v1.getId(), v2.getId());
    }

    // -------- AC: cross-module SPI surfaces a contributor's aggregate --------------------------

    @Test
    void consolidate_crossModuleContributor_surfacesAggregate() {
        final Application app = newApplication(tenantId, "Billing");

        final ApplicationConsolidation c = consolidationService.consolidate(tenantId, app.getId());

        // no-op + fake budget contributor are both wired; only the fake one contributes
        assertThat(c.contributions()).hasSize(1);
        final ApplicationAggregateContribution budget = c.contributions().get(0);
        assertThat(budget.moduleId()).isEqualTo("budget");
        assertThat(budget.metrics()).containsEntry("applicationId", app.getId());
        assertThat(budget.metrics()).containsEntry("consumedRatio", 0.5);
    }

    // -------- AC: project → application resolution is deterministic (single parent) -------------

    @Test
    void resolver_projectTracesToExactlyOneApplication() {
        final Application app = newApplication(tenantId, "Billing");
        final Project v1 = newProject(tenantId, app, "v1");

        assertThat(resolver.resolveApplicationId(tenantId, v1.getId())).isEqualTo(app.getId());
    }

    // -------- Security: a foreign tenant's application is invisible (404 equivalent) ------------

    @Test
    void consolidate_foreignTenantApplication_notAccessible() throws Exception {
        final long tenantT2 = seedTenant();
        final Application appT1 = newApplication(tenantId, "Billing");
        newProject(tenantId, appT1, "v1");

        assertThatThrownBy(() -> consolidationService.consolidate(tenantT2, appT1.getId()))
                .isInstanceOf(ApplicationNotFoundException.class);
    }

    // -------- Security: a foreign tenant cannot resolve another tenant's project ----------------

    @Test
    void resolver_foreignTenantProject_notAccessible() throws Exception {
        final long tenantT2 = seedTenant();
        final Application appT1 = newApplication(tenantId, "Billing");
        final Project v1 = newProject(tenantId, appT1, "v1");

        assertThatThrownBy(() -> resolver.resolveApplicationId(tenantT2, v1.getId()))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    // -------- Error: unknown application → ApplicationNotFoundException -------------------------

    @Test
    void consolidate_unknownApplication_throws() {
        assertThatThrownBy(() -> consolidationService.consolidate(tenantId, 999_999L))
                .isInstanceOf(ApplicationNotFoundException.class);
    }
}

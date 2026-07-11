package fr.pivot.pilotage.roadmap;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link RoadmapService} (US22.3.1) against a real PostgreSQL 18
 * (Testcontainers), with this module's Flyway migration applied — covers the full lane/initiative
 * lifecycle, the {@code pilotage.lane} unique-label constraint, and the multi-tenant/multi-team
 * isolation security AC. {@code public.tenants}/{@code public.teams} are seeded before Flyway via
 * {@link PlatformSchemaTestSupport}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RoadmapServiceIT {

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
    @Autowired private LaneRepository laneRepository;
    @Autowired private RoadmapService roadmapService;

    private long tenantId;
    private long teamId;
    private long projectId;

    /** Seeds a fresh tenant/team/application/project before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
        teamId = seedTeam(tenantId);
        projectId = newProject(tenantId, teamId).getId();
    }

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
        return projectRepository.save(new Project(app, owner, team, "P", now));
    }

    // -------- AC: an initiative can be posed without a period (no dates, no child tasks) --------

    @Test
    void createInitiative_withoutPeriod_createsInitiativeVisibleInListing() {
        final LaneResponse laneA = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));

        final InitiativeResponse created = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Launch v1", laneA.id(), null, null, null));

        assertThat(created.fuzzyPeriodStart()).isNull();
        assertThat(created.fuzzyPeriodEnd()).isNull();

        final List<InitiativeResponse> listed = roadmapService.listInitiatives(tenantId, teamId, projectId);
        assertThat(listed).extracting(InitiativeResponse::id).containsExactly(created.id());
    }

    // -------- AC: moving/resizing an initiative updates its approximate period immediately -------

    @Test
    void updatePlacement_resizesInitiativePeriod() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));
        final InitiativeResponse created = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Launch v1", lane.id(), null, null, null));

        final LocalDate start = LocalDate.of(2026, 1, 1);
        final LocalDate end = LocalDate.of(2026, 3, 31);
        final InitiativeResponse moved = roadmapService.updatePlacement(tenantId, teamId, projectId, created.id(),
                new UpdateInitiativePlacementRequest(null, start, end));

        assertThat(moved.fuzzyPeriodStart()).isEqualTo(start);
        assertThat(moved.fuzzyPeriodEnd()).isEqualTo(end);
        assertThat(moved.revision()).isEqualTo(created.revision() + 1);
    }

    // -------- Error case: no target lane → rejected -----------------------------------------------

    @Test
    void createInitiative_unknownLane_rejected() {
        assertThatExceptionOfType(LaneNotFoundException.class).isThrownBy(() ->
                roadmapService.createInitiative(tenantId, teamId, projectId,
                        new CreateInitiativeRequest("No lane", 999_999L, null, null, null)));
    }

    // -------- Error case (schema): duplicate lane label on the same project is rejected -----------

    @Test
    void createLane_duplicateLabel_rejectedAndNoSecondRowWritten() {
        roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));

        assertThatExceptionOfType(DuplicateLaneNameException.class).isThrownBy(() ->
                roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("theme a")));

        assertThat(laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(
                projectId, tenantId, teamId)).hasSize(1);
    }

    // -------- Error case (schema): an unknown lane_id FK is rejected at the DB level ---------------

    @Test
    void laneForeignKey_unknownProjectId_violatesReferentialIntegrity() {
        assertThatThrownBy(() -> laneRepository.saveAndFlush(new Lane(tenantId, teamId, 999_999_999L, "Ghost", 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------- Security AC: cross-tenant/cross-team isolation (404-equivalent) ----------------------

    @Test
    void crossTenant_projectNotVisible_throwsProjectNotFound() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                roadmapService.listLanes(otherTenant, otherTeam, projectId));
    }

    @Test
    void crossTeam_sameTenant_projectNotVisible_throwsProjectNotFound() throws Exception {
        final long otherTeamSameTenant = seedTeam(tenantId);

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                roadmapService.listLanes(tenantId, otherTeamSameTenant, projectId));
    }

    @Test
    void crossTenant_laneNotUsableToCreateInitiativeUnderForeignProject() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);
        final long otherProjectId = newProject(otherTenant, otherTeam).getId();
        final LaneResponse ownLane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Mine"));

        // A lane id that is real (belongs to tenant/team/project A) must not be usable to create an
        // initiative under a different project (B), even within a different tenant scope entirely.
        assertThatExceptionOfType(LaneNotFoundException.class).isThrownBy(() ->
                roadmapService.createInitiative(otherTenant, otherTeam, otherProjectId,
                        new CreateInitiativeRequest("Smuggled", ownLane.id(), null, null, null)));
    }
}

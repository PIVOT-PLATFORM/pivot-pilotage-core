package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.TemporalPrecision;
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
                new CreateInitiativeRequest("Launch v1", laneA.id(), null, null, null, null));

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
                new CreateInitiativeRequest("Launch v1", lane.id(), null, null, null, null));

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
                        new CreateInitiativeRequest("No lane", 999_999L, null, null, null, null)));
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
                        new CreateInitiativeRequest("Smuggled", ownLane.id(), null, null, null, null)));
    }

    // -------- scale (US22.3.2) ---------------------------------------------------------------------

    // -------- AC: default scale comes from the tenant's default profile (EN18.10) ------------------

    @Test
    void getScale_freshProject_derivesDefaultFromProfile() {
        final RoadmapScaleResponse scale = roadmapService.getScale(tenantId, teamId, projectId);

        // Versioned default profile altitude is MACRO ⇒ QUARTER scale, and it is not an explicit setting.
        assertThat(scale.scale()).isEqualTo(TemporalPrecision.QUARTER);
        assertThat(scale.explicit()).isFalse();
    }

    // -------- AC: choosing a scale aligns bars on period bounds, not on day-precise dates ----------

    @Test
    void updateScale_thenListInitiatives_snapsBarsToChosenScale() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));
        roadmapService.createInitiative(tenantId, teamId, projectId, new CreateInitiativeRequest(
                "Feb push", lane.id(), LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 20), null, null));

        roadmapService.updateScale(tenantId, teamId, projectId, new UpdateRoadmapScaleRequest(TemporalPrecision.MONTH));

        final InitiativeResponse only = roadmapService.listInitiatives(tenantId, teamId, projectId).get(0);
        // Raw period preserved; bar snapped to the whole month of February.
        assertThat(only.fuzzyPeriodStart()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(only.periodBounds().start()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(only.periodBounds().end()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    // -------- Error AC: changing scale never deletes/truncates existing period data ----------------

    @Test
    void updateScale_doesNotAlterStoredInitiativePeriods() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));
        final InitiativeResponse created = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Q1", lane.id(), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 25),
                        null, null));

        // Switch QUARTER → MONTH → DAY; the stored fuzzy period must survive untouched.
        roadmapService.updateScale(tenantId, teamId, projectId, new UpdateRoadmapScaleRequest(TemporalPrecision.MONTH));
        roadmapService.updateScale(tenantId, teamId, projectId, new UpdateRoadmapScaleRequest(TemporalPrecision.DAY));

        final InitiativeResponse reread = roadmapService.listInitiatives(tenantId, teamId, projectId).stream()
                .filter(i -> i.id() == created.id()).findFirst().orElseThrow();
        assertThat(reread.fuzzyPeriodStart()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(reread.fuzzyPeriodEnd()).isEqualTo(LocalDate.of(2026, 3, 25));
        // At DAY scale the bar equals the raw period exactly (no snapping loss).
        assertThat(reread.periodBounds().start()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(reread.periodBounds().end()).isEqualTo(LocalDate.of(2026, 3, 25));
    }

    @Test
    void updateScale_roundTripsAsExplicitSetting() {
        roadmapService.updateScale(tenantId, teamId, projectId,
                new UpdateRoadmapScaleRequest(TemporalPrecision.SEMESTER));

        final RoadmapScaleResponse scale = roadmapService.getScale(tenantId, teamId, projectId);
        assertThat(scale.scale()).isEqualTo(TemporalPrecision.SEMESTER);
        assertThat(scale.explicit()).isTrue();
    }

    // -------- Security AC: scale is per-roadmap and cross-tenant isolated (404-equivalent) ---------

    @Test
    void crossTenant_scaleNotReadableUnderForeignProject() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                roadmapService.getScale(otherTenant, otherTeam, projectId));
    }

    // -------- Now / Next / Later (US22.3.3) --------------------------------------------------------

    // -------- AC: a freshly created initiative defaults to the NOW bucket --------------------------

    @Test
    void createInitiative_defaultsHorizonToNow() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));

        final InitiativeResponse created = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Default bucket", lane.id(), null, null, null, null));

        assertThat(created.horizon()).isEqualTo(Horizon.NOW);
    }

    // -------- AC: initiatives are ranged in columns by horizon ------------------------------------

    @Test
    void listHorizonView_rangesInitiativesInColumnsByHorizon() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));
        final InitiativeResponse now = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Now item", lane.id(), null, null, null, Horizon.NOW));
        final InitiativeResponse later = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Later item", lane.id(), null, null, null, Horizon.LATER));

        final HorizonViewResponse view = roadmapService.listHorizonView(tenantId, teamId, projectId);

        assertThat(view.buckets()).extracting(HorizonBucketResponse::horizon)
                .containsExactly(Horizon.NOW, Horizon.NEXT, Horizon.LATER);
        assertThat(view.buckets().get(0).initiatives()).extracting(InitiativeResponse::id).containsExactly(now.id());
        assertThat(view.buckets().get(2).initiatives()).extracting(InitiativeResponse::id).containsExactly(later.id());
    }

    // -------- AC: dragging an initiative to another bucket updates its horizon ---------------------

    @Test
    void updateHorizon_movesInitiativeToAnotherBucket() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));
        final InitiativeResponse created = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Movable", lane.id(), null, null, null, Horizon.NOW));

        final InitiativeResponse moved = roadmapService.updateHorizon(tenantId, teamId, projectId, created.id(),
                new UpdateInitiativeHorizonRequest(Horizon.NEXT));

        assertThat(moved.horizon()).isEqualTo(Horizon.NEXT);
        assertThat(moved.revision()).isEqualTo(created.revision() + 1);

        final HorizonViewResponse view = roadmapService.listHorizonView(tenantId, teamId, projectId);
        assertThat(view.buckets().get(1).initiatives()).extracting(InitiativeResponse::id).containsExactly(created.id());
    }

    // -------- Security AC: horizon move is cross-tenant isolated (404-equivalent) ------------------

    @Test
    void crossTenant_horizonNotChangeableUnderForeignProject() throws Exception {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));
        final InitiativeResponse created = roadmapService.createInitiative(tenantId, teamId, projectId,
                new CreateInitiativeRequest("Mine", lane.id(), null, null, null, Horizon.NOW));
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);
        final long otherProjectId = newProject(otherTenant, otherTeam).getId();

        assertThatExceptionOfType(InitiativeNotFoundException.class).isThrownBy(() ->
                roadmapService.updateHorizon(otherTenant, otherTeam, otherProjectId, created.id(),
                        new UpdateInitiativeHorizonRequest(Horizon.LATER)));
    }

    // -------- milestones (US22.3.4) ----------------------------------------------------------------

    // -------- AC: a milestone is visible on the roadmap (and, same row, would be to a future Gantt) --

    @Test
    void createMilestone_withoutLane_createsProjectWideMarkerVisibleInListing() {
        final LocalDate date = LocalDate.of(2026, 6, 15);

        final MilestoneResponse created = roadmapService.createMilestone(tenantId, teamId, projectId,
                new CreateMilestoneRequest("Board review", date, null));

        assertThat(created.laneId()).isNull();
        assertThat(created.date()).isEqualTo(date);

        final List<MilestoneResponse> listed = roadmapService.listMilestones(tenantId, teamId, projectId);
        assertThat(listed).extracting(MilestoneResponse::id).containsExactly(created.id());
    }

    @Test
    void createMilestone_pinnedToLane_isPersistedWithThatLane() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));

        final MilestoneResponse created = roadmapService.createMilestone(tenantId, teamId, projectId,
                new CreateMilestoneRequest("Beta launch", LocalDate.of(2026, 5, 1), lane.id()));

        assertThat(created.laneId()).isEqualTo(lane.id());
    }

    // -------- AC: given a milestone, when its date changes, then the roadmap reflects the change ----

    @Test
    void updateMilestone_dateChange_isReflectedImmediatelyInListing() {
        final MilestoneResponse created = roadmapService.createMilestone(tenantId, teamId, projectId,
                new CreateMilestoneRequest("Launch", LocalDate.of(2026, 3, 1), null));

        final LocalDate newDate = LocalDate.of(2026, 4, 15);
        final MilestoneResponse moved = roadmapService.updateMilestone(tenantId, teamId, projectId, created.id(),
                new UpdateMilestoneRequest(newDate, null));

        assertThat(moved.date()).isEqualTo(newDate);
        assertThat(moved.revision()).isEqualTo(created.revision() + 1);

        final List<MilestoneResponse> listed = roadmapService.listMilestones(tenantId, teamId, projectId);
        assertThat(listed).extracting(MilestoneResponse::date).containsExactly(newDate);
    }

    // -------- Error case: a milestone without a date is rejected -----------------------------------

    @Test
    void createMilestone_withoutDate_rejected() {
        final InvalidMilestoneDateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidMilestoneDateException.class, () -> roadmapService.createMilestone(
                        tenantId, teamId, projectId, new CreateMilestoneRequest("No date", null, null)));

        assertThat(ex.code()).isEqualTo(InvalidMilestoneDateException.CODE_REQUIRED);
    }

    // -------- Error case: a milestone date outside the project's known footprint is rejected -------

    @Test
    void createMilestone_dateOutsideProjectFootprint_rejected() {
        final LaneResponse lane = roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));
        roadmapService.createInitiative(tenantId, teamId, projectId, new CreateInitiativeRequest(
                "Only initiative", lane.id(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null, null));

        final InvalidMilestoneDateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidMilestoneDateException.class, () -> roadmapService.createMilestone(
                        tenantId, teamId, projectId,
                        new CreateMilestoneRequest("Too late", LocalDate.of(2027, 1, 1), null)));

        assertThat(ex.code()).isEqualTo(InvalidMilestoneDateException.CODE_OUT_OF_BOUNDS);
    }

    @Test
    void createMilestone_unknownLane_rejected() {
        assertThatExceptionOfType(LaneNotFoundException.class).isThrownBy(() ->
                roadmapService.createMilestone(tenantId, teamId, projectId,
                        new CreateMilestoneRequest("No lane", LocalDate.of(2026, 1, 1), 999_999L)));
    }

    @Test
    void updateMilestone_unknownMilestone_rejected() {
        assertThatExceptionOfType(MilestoneNotFoundException.class).isThrownBy(() ->
                roadmapService.updateMilestone(tenantId, teamId, projectId, 999_999L,
                        new UpdateMilestoneRequest(LocalDate.of(2026, 1, 1), null)));
    }

    // -------- Security AC: cross-tenant/cross-team isolation on milestones (404-equivalent) --------

    @Test
    void crossTenant_milestoneListing_projectNotVisible_throwsProjectNotFound() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                roadmapService.listMilestones(otherTenant, otherTeam, projectId));
    }

    @Test
    void crossTenant_milestoneNotReachableForUpdateUnderForeignProject() throws Exception {
        final MilestoneResponse created = roadmapService.createMilestone(tenantId, teamId, projectId,
                new CreateMilestoneRequest("Launch", LocalDate.of(2026, 3, 1), null));
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);
        final long otherProjectId = newProject(otherTenant, otherTeam).getId();

        assertThatExceptionOfType(MilestoneNotFoundException.class).isThrownBy(() ->
                roadmapService.updateMilestone(otherTenant, otherTeam, otherProjectId, created.id(),
                        new UpdateMilestoneRequest(LocalDate.of(2026, 4, 1), null)));
    }
}

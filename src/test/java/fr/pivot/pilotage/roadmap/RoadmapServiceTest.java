package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.profile.DefaultOrganizationProfile;
import fr.pivot.pilotage.profile.OrganizationProfileResolver;
import fr.pivot.pilotage.profile.RigorLevel;
import fr.pivot.pilotage.profile.SovereigntyClass;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.projection.Altitude;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoadmapService} with mocked repositories (US22.3.1) — exercises every
 * branch (project resolution, lane duplicate/lookup, initiative creation/listing/placement,
 * period validation) without a database, complementing {@link RoadmapServiceIT}.
 */
@ExtendWith(MockitoExtension.class)
class RoadmapServiceTest {

    private static final long TENANT = 7L;
    private static final long TEAM = 42L;
    private static final long PROJECT = 100L;

    @Mock private ProjectRepository projectRepository;
    @Mock private LaneRepository laneRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private OrganizationProfileResolver profileResolver;

    private RoadmapService service;

    @BeforeEach
    void setUp() {
        service = new RoadmapService(projectRepository, laneRepository, taskRepository, profileResolver);
        lenient().when(projectRepository.findByIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(new Project(null, TENANT, TEAM, "P", Instant.now())));
        lenient().when(profileResolver.resolveProfile(TENANT)).thenReturn(new DefaultOrganizationProfile(
                Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, java.util.Set.of("roadmap")));
    }

    private static void setId(final Object entity, final long id) {
        try {
            final Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Lane lane(final long id, final String name, final int position) {
        final Lane lane = new Lane(TENANT, TEAM, PROJECT, name, position);
        setId(lane, id);
        return lane;
    }

    private static Task initiative(final long id, final long laneId, final int position, final String name) {
        final Task task = new Task(TENANT, TEAM, PROJECT, position, name, NodeKind.LEAF, Boolean.TRUE,
                TemporalPrecision.QUARTER, 0);
        task.setLaneId(laneId);
        setId(task, id);
        return task;
    }

    /**
     * Builds a plain initiative-like task carrying an explicit fuzzy period, used to seed the
     * project's temporal footprint that milestone bounds are derived from.
     */
    private static Task datedTask(final long id, final LocalDate start, final LocalDate end) {
        final Task task = new Task(TENANT, TEAM, PROJECT, 0, "Other", NodeKind.LEAF, Boolean.TRUE,
                TemporalPrecision.QUARTER, 0);
        task.setFuzzyPeriodStart(start);
        task.setFuzzyPeriodEnd(end);
        setId(task, id);
        return task;
    }

    private static Task milestone(final long id, final Long laneId, final int position, final String name,
            final LocalDate date) {
        final Task task = new Task(TENANT, TEAM, PROJECT, position, name, NodeKind.MILESTONE, Boolean.TRUE,
                TemporalPrecision.DAY, 0);
        task.setLaneId(laneId);
        task.setDurationMinutes(0);
        if (date != null) {
            task.setFuzzyPeriodStart(date);
            task.setFuzzyPeriodEnd(date);
            final java.time.Instant atMidnightUtc = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            task.setStartDate(atMidnightUtc);
            task.setFinishDate(atMidnightUtc);
        }
        setId(task, id);
        return task;
    }

    // ---- project resolution shared by every operation ------------------------------------------

    @Test
    void listLanes_unknownProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndTenantIdAndTeamId(999L, TENANT, TEAM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ProjectNotFoundException.class)
                .isThrownBy(() -> service.listLanes(TENANT, TEAM, 999L));
    }

    // ---- lanes ------------------------------------------------------------------------------------

    @Test
    void listLanes_returnsLanesOrderedAsReturnedByRepository() {
        when(laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(lane(1L, "Team Alpha", 0), lane(2L, "Team Beta", 1)));

        final List<LaneResponse> result = service.listLanes(TENANT, TEAM, PROJECT);

        assertThat(result).extracting(LaneResponse::name).containsExactly("Team Alpha", "Team Beta");
        assertThat(result).extracting(LaneResponse::position).containsExactly(0, 1);
    }

    @Test
    void createLane_appendsAtEndOfExistingLanes() {
        when(laneRepository.existsByProjectIdAndTenantIdAndTeamIdAndNameIgnoreCase(PROJECT, TENANT, TEAM, "New lane"))
                .thenReturn(false);
        when(laneRepository.countByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(2L);
        when(laneRepository.save(any(Lane.class))).thenAnswer(inv -> {
            final Lane saved = inv.getArgument(0);
            setId(saved, 10L);
            return saved;
        });

        final LaneResponse response = service.createLane(TENANT, TEAM, PROJECT, new CreateLaneRequest("New lane"));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("New lane");
        assertThat(response.position()).isEqualTo(2);
    }

    @Test
    void createLane_duplicateName_throwsDuplicateLaneName() {
        when(laneRepository.existsByProjectIdAndTenantIdAndTeamIdAndNameIgnoreCase(PROJECT, TENANT, TEAM, "Dup"))
                .thenReturn(true);

        assertThatExceptionOfType(DuplicateLaneNameException.class).isThrownBy(
                () -> service.createLane(TENANT, TEAM, PROJECT, new CreateLaneRequest("Dup")));

        verify(laneRepository, never()).save(any());
    }

    // ---- initiatives: listing ----------------------------------------------------------------------

    @Test
    void listInitiatives_sortsByLanePositionThenTaskPosition() {
        when(laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(lane(1L, "Lane A", 0), lane(2L, "Lane B", 1)));
        // Deliberately out of order in the repository result to prove the service re-sorts.
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndLaneIdIsNotNull(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(
                        initiative(30L, 2L, 0, "In lane B"),
                        initiative(31L, 1L, 1, "Second in lane A"),
                        initiative(32L, 1L, 0, "First in lane A")));

        final List<InitiativeResponse> result = service.listInitiatives(TENANT, TEAM, PROJECT);

        assertThat(result).extracting(InitiativeResponse::name)
                .containsExactly("First in lane A", "Second in lane A", "In lane B");
    }

    @Test
    void listInitiatives_noInitiatives_returnsEmptyList() {
        when(laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(PROJECT, TENANT, TEAM))
                .thenReturn(List.of());
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndLaneIdIsNotNull(PROJECT, TENANT, TEAM))
                .thenReturn(List.of());

        assertThat(service.listInitiatives(TENANT, TEAM, PROJECT)).isEmpty();
    }

    // ---- initiatives: creation ----------------------------------------------------------------------

    @Test
    void createInitiative_noPeriod_defaultsPrecisionToQuarter() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(1L, "Lane A", 0)));
        when(taskRepository.countByLaneIdAndTenantIdAndTeamId(1L, TENANT, TEAM)).thenReturn(0L);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            final Task saved = inv.getArgument(0);
            setId(saved, 50L);
            return saved;
        });

        final InitiativeResponse response = service.createInitiative(TENANT, TEAM, PROJECT,
                new CreateInitiativeRequest("Initiative", 1L, null, null, null, null));

        assertThat(response.id()).isEqualTo(50L);
        assertThat(response.laneId()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Initiative");
        assertThat(response.fuzzyPeriodStart()).isNull();
        assertThat(response.fuzzyPeriodEnd()).isNull();
        assertThat(response.temporalPrecision()).isEqualTo(TemporalPrecision.QUARTER);
        assertThat(response.horizon()).isEqualTo(Horizon.NOW);
        assertThat(response.revision()).isZero();
    }

    @Test
    void createInitiative_explicitPrecisionAndPeriod_persistsAsGiven() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(1L, "Lane A", 0)));
        when(taskRepository.countByLaneIdAndTenantIdAndTeamId(1L, TENANT, TEAM)).thenReturn(3L);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            final Task saved = inv.getArgument(0);
            setId(saved, 51L);
            return saved;
        });
        final LocalDate start = LocalDate.of(2026, 1, 1);
        final LocalDate end = LocalDate.of(2026, 3, 31);

        final InitiativeResponse response = service.createInitiative(TENANT, TEAM, PROJECT,
                new CreateInitiativeRequest("Q1 push", 1L, start, end, TemporalPrecision.MONTH, null));

        assertThat(response.fuzzyPeriodStart()).isEqualTo(start);
        assertThat(response.fuzzyPeriodEnd()).isEqualTo(end);
        assertThat(response.temporalPrecision()).isEqualTo(TemporalPrecision.MONTH);
    }

    @Test
    void createInitiative_unknownLane_throwsLaneNotFound() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(999L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.empty());

        final LaneNotFoundException ex = Assertions.assertThrows(
                LaneNotFoundException.class, () -> service.createInitiative(
                        TENANT, TEAM, PROJECT, new CreateInitiativeRequest("X", 999L, null, null, null, null)));
        assertThat(ex.code()).isEqualTo(LaneNotFoundException.CODE_NOT_FOUND);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void createInitiative_noLaneIdAtAll_throwsLaneNotFoundMissing() {
        final LaneNotFoundException ex = Assertions.assertThrows(
                LaneNotFoundException.class, () -> service.createInitiative(
                        TENANT, TEAM, PROJECT, new CreateInitiativeRequest("X", null, null, null, null, null)));

        assertThat(ex.code()).isEqualTo(LaneNotFoundException.CODE_REQUIRED);
        assertThat(ex.getMessage()).contains("lane");
        verify(laneRepository, never()).findByIdAndProjectIdAndTenantIdAndTeamId(any(), any(), any(), any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createInitiative_onlyOneBoundSupplied_throwsInvalidPeriod() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(1L, "Lane A", 0)));

        assertThatExceptionOfType(InvalidInitiativePeriodException.class).isThrownBy(
                () -> service.createInitiative(TENANT, TEAM, PROJECT,
                        new CreateInitiativeRequest("X", 1L, LocalDate.of(2026, 1, 1), null, null, null)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    void createInitiative_endBeforeStart_throwsInvalidPeriod() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(1L, "Lane A", 0)));

        assertThatExceptionOfType(InvalidInitiativePeriodException.class).isThrownBy(
                () -> service.createInitiative(TENANT, TEAM, PROJECT, new CreateInitiativeRequest(
                        "X", 1L, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1), null, null)));

        verify(taskRepository, never()).save(any());
    }

    // ---- initiatives: placement update -----------------------------------------------------------

    @Test
    void updatePlacement_periodOnly_updatesFieldsAndBumpsRevision() {
        final Task existing = initiative(50L, 1L, 0, "Initiative");
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(50L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        final LocalDate start = LocalDate.of(2026, 4, 1);
        final LocalDate end = LocalDate.of(2026, 6, 30);

        final InitiativeResponse response = service.updatePlacement(TENANT, TEAM, PROJECT, 50L,
                new UpdateInitiativePlacementRequest(null, start, end));

        assertThat(response.fuzzyPeriodStart()).isEqualTo(start);
        assertThat(response.fuzzyPeriodEnd()).isEqualTo(end);
        assertThat(response.laneId()).isEqualTo(1L);
        assertThat(response.revision()).isEqualTo(1);
    }

    @Test
    void updatePlacement_laneOnly_reassignsLaneAndBumpsRevision() {
        final Task existing = initiative(50L, 1L, 0, "Initiative");
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(50L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(2L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(2L, "Lane B", 1)));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final InitiativeResponse response = service.updatePlacement(TENANT, TEAM, PROJECT, 50L,
                new UpdateInitiativePlacementRequest(2L, null, null));

        assertThat(response.laneId()).isEqualTo(2L);
        assertThat(response.revision()).isEqualTo(1);
    }

    @Test
    void updatePlacement_emptyRequest_isNoOpAndRevisionUnchanged() {
        final Task existing = initiative(50L, 1L, 0, "Initiative");
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(50L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final InitiativeResponse response = service.updatePlacement(TENANT, TEAM, PROJECT, 50L,
                new UpdateInitiativePlacementRequest(null, null, null));

        assertThat(response.revision()).isZero();
    }

    @Test
    void updatePlacement_unknownInitiative_throwsInitiativeNotFound() {
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(999L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(InitiativeNotFoundException.class).isThrownBy(
                () -> service.updatePlacement(TENANT, TEAM, PROJECT, 999L,
                        new UpdateInitiativePlacementRequest(null, null, null)));
    }

    @Test
    void updatePlacement_taskWithoutLane_isNotAnInitiative_throwsInitiativeNotFound() {
        final Task plainGanttTask = new Task(TENANT, TEAM, PROJECT, 0, "Detail task", NodeKind.LEAF,
                Boolean.FALSE, TemporalPrecision.DAY, 0);
        setId(plainGanttTask, 60L);
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(60L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(plainGanttTask));

        assertThatExceptionOfType(InitiativeNotFoundException.class).isThrownBy(
                () -> service.updatePlacement(TENANT, TEAM, PROJECT, 60L,
                        new UpdateInitiativePlacementRequest(null, null, null)));
    }

    @Test
    void updatePlacement_unknownLane_throwsLaneNotFound() {
        final Task existing = initiative(50L, 1L, 0, "Initiative");
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(50L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(eq(999L), eq(PROJECT), eq(TENANT), eq(TEAM)))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(LaneNotFoundException.class).isThrownBy(
                () -> service.updatePlacement(TENANT, TEAM, PROJECT, 50L,
                        new UpdateInitiativePlacementRequest(999L, null, null)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    void updatePlacement_onlyOneBoundSupplied_throwsInvalidPeriod() {
        final Task existing = initiative(50L, 1L, 0, "Initiative");
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(50L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));

        assertThatExceptionOfType(InvalidInitiativePeriodException.class).isThrownBy(
                () -> service.updatePlacement(TENANT, TEAM, PROJECT, 50L,
                        new UpdateInitiativePlacementRequest(null, LocalDate.of(2026, 1, 1), null)));

        verify(taskRepository, never()).save(any());
    }

    // ---- milestones (US22.3.4): listing -----------------------------------------------------------

    @Test
    void listMilestones_sortsByDateThenId() {
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndNodeKind(PROJECT, TENANT, TEAM,
                NodeKind.MILESTONE)).thenReturn(List.of(
                        milestone(30L, null, 0, "Later one", LocalDate.of(2026, 6, 1)),
                        milestone(31L, null, 1, "Earliest", LocalDate.of(2026, 1, 1)),
                        milestone(32L, null, 2, "Undated", null)));

        final List<MilestoneResponse> result = service.listMilestones(TENANT, TEAM, PROJECT);

        assertThat(result).extracting(MilestoneResponse::name)
                .containsExactly("Earliest", "Later one", "Undated");
    }

    @Test
    void listMilestones_none_returnsEmptyList() {
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndNodeKind(PROJECT, TENANT, TEAM,
                NodeKind.MILESTONE)).thenReturn(List.of());

        assertThat(service.listMilestones(TENANT, TEAM, PROJECT)).isEmpty();
    }

    // ---- milestones (US22.3.4): creation -----------------------------------------------------------

    @Test
    void createMilestone_noOtherProjectData_anyDateAccepted_populatesBothTemporalRepresentations() {
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(List.of());
        when(taskRepository.countByProjectIdAndTenantIdAndTeamIdAndNodeKind(PROJECT, TENANT, TEAM, NodeKind.MILESTONE))
                .thenReturn(0L);
        final Task[] captured = new Task[1];
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            final Task saved = inv.getArgument(0);
            setId(saved, 70L);
            captured[0] = saved;
            return saved;
        });
        final LocalDate date = LocalDate.of(2026, 9, 15);

        final MilestoneResponse response = service.createMilestone(TENANT, TEAM, PROJECT,
                new CreateMilestoneRequest("Go/No-Go", date, null));

        assertThat(response.id()).isEqualTo(70L);
        assertThat(response.laneId()).isNull();
        assertThat(response.name()).isEqualTo("Go/No-Go");
        assertThat(response.date()).isEqualTo(date);
        assertThat(response.temporalPrecision()).isEqualTo(TemporalPrecision.DAY);
        assertThat(response.revision()).isZero();
        // Same object, no transformation for a future Gantt consumer: precise bounds populated too.
        assertThat(captured[0].getStartDate()).isEqualTo(date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        assertThat(captured[0].getFinishDate()).isEqualTo(captured[0].getStartDate());
        assertThat(captured[0].getDurationMinutes()).isZero();
        assertThat(captured[0].getNodeKind()).isEqualTo(NodeKind.MILESTONE);
    }

    @Test
    void createMilestone_withLane_persistsLaneId() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(1L, "Lane A", 0)));
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(List.of());
        when(taskRepository.countByProjectIdAndTenantIdAndTeamIdAndNodeKind(PROJECT, TENANT, TEAM, NodeKind.MILESTONE))
                .thenReturn(1L);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            final Task saved = inv.getArgument(0);
            setId(saved, 71L);
            return saved;
        });

        final MilestoneResponse response = service.createMilestone(TENANT, TEAM, PROJECT,
                new CreateMilestoneRequest("Beta launch", LocalDate.of(2026, 5, 1), 1L));

        assertThat(response.laneId()).isEqualTo(1L);
    }

    @Test
    void createMilestone_noDate_throwsInvalidMilestoneDateMissing() {
        final InvalidMilestoneDateException ex = Assertions.assertThrows(InvalidMilestoneDateException.class,
                () -> service.createMilestone(TENANT, TEAM, PROJECT,
                        new CreateMilestoneRequest("No date", null, null)));

        assertThat(ex.code()).isEqualTo(InvalidMilestoneDateException.CODE_REQUIRED);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createMilestone_unknownLane_throwsLaneNotFound() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(999L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(LaneNotFoundException.class).isThrownBy(() ->
                service.createMilestone(TENANT, TEAM, PROJECT,
                        new CreateMilestoneRequest("X", LocalDate.of(2026, 1, 1), 999L)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    void createMilestone_dateWithinExistingProjectFootprint_isAccepted() {
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(
                List.of(datedTask(20L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))));
        when(taskRepository.countByProjectIdAndTenantIdAndTeamIdAndNodeKind(PROJECT, TENANT, TEAM, NodeKind.MILESTONE))
                .thenReturn(0L);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            final Task saved = inv.getArgument(0);
            setId(saved, 72L);
            return saved;
        });

        final MilestoneResponse response = service.createMilestone(TENANT, TEAM, PROJECT,
                new CreateMilestoneRequest("Mid-year review", LocalDate.of(2026, 6, 15), null));

        assertThat(response.date()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void createMilestone_dateBeforeProjectFootprint_throwsOutOfBounds() {
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(
                List.of(datedTask(20L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))));

        final InvalidMilestoneDateException ex = Assertions.assertThrows(InvalidMilestoneDateException.class,
                () -> service.createMilestone(TENANT, TEAM, PROJECT,
                        new CreateMilestoneRequest("Too early", LocalDate.of(2025, 12, 31), null)));

        assertThat(ex.code()).isEqualTo(InvalidMilestoneDateException.CODE_OUT_OF_BOUNDS);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createMilestone_dateAfterProjectFootprint_throwsOutOfBounds() {
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(
                List.of(datedTask(20L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))));

        final InvalidMilestoneDateException ex = Assertions.assertThrows(InvalidMilestoneDateException.class,
                () -> service.createMilestone(TENANT, TEAM, PROJECT,
                        new CreateMilestoneRequest("Too late", LocalDate.of(2027, 1, 1), null)));

        assertThat(ex.code()).isEqualTo(InvalidMilestoneDateException.CODE_OUT_OF_BOUNDS);
        verify(taskRepository, never()).save(any());
    }

    // ---- milestones (US22.3.4): move/update ------------------------------------------------------

    @Test
    void updateMilestone_dateOnly_updatesBothRepresentationsAndBumpsRevision() {
        final Task existing = milestone(80L, null, 0, "Launch", LocalDate.of(2026, 3, 1));
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(80L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        final LocalDate newDate = LocalDate.of(2026, 4, 1);

        final MilestoneResponse response = service.updateMilestone(TENANT, TEAM, PROJECT, 80L,
                new UpdateMilestoneRequest(newDate, null));

        assertThat(response.date()).isEqualTo(newDate);
        assertThat(response.revision()).isEqualTo(1);
    }

    @Test
    void updateMilestone_isNeverBoundedByItsOwnPreviousDate() {
        // The milestone is the ONLY dated element on the project; moving it must never be rejected
        // as "out of its own bounds" — the envelope excludes the task being moved.
        final Task existing = milestone(81L, null, 0, "Launch", LocalDate.of(2026, 3, 1));
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(81L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final MilestoneResponse response = service.updateMilestone(TENANT, TEAM, PROJECT, 81L,
                new UpdateMilestoneRequest(LocalDate.of(2030, 1, 1), null));

        assertThat(response.date()).isEqualTo(LocalDate.of(2030, 1, 1));
    }

    @Test
    void updateMilestone_laneOnly_reassignsLaneAndBumpsRevision() {
        final Task existing = milestone(82L, null, 0, "Launch", LocalDate.of(2026, 3, 1));
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(82L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(2L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(2L, "Lane B", 1)));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final MilestoneResponse response = service.updateMilestone(TENANT, TEAM, PROJECT, 82L,
                new UpdateMilestoneRequest(null, 2L));

        assertThat(response.laneId()).isEqualTo(2L);
        assertThat(response.revision()).isEqualTo(1);
    }

    @Test
    void updateMilestone_emptyRequest_isNoOpAndRevisionUnchanged() {
        final Task existing = milestone(83L, null, 0, "Launch", LocalDate.of(2026, 3, 1));
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(83L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final MilestoneResponse response = service.updateMilestone(TENANT, TEAM, PROJECT, 83L,
                new UpdateMilestoneRequest(null, null));

        assertThat(response.revision()).isZero();
    }

    @Test
    void updateMilestone_unknownMilestone_throwsMilestoneNotFound() {
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(999L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(MilestoneNotFoundException.class).isThrownBy(() ->
                service.updateMilestone(TENANT, TEAM, PROJECT, 999L, new UpdateMilestoneRequest(null, null)));
    }

    @Test
    void updateMilestone_taskThatIsNotAMilestone_throwsMilestoneNotFound() {
        final Task plainInitiative = initiative(90L, 1L, 0, "Initiative, not a milestone");
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(90L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(plainInitiative));

        assertThatExceptionOfType(MilestoneNotFoundException.class).isThrownBy(() ->
                service.updateMilestone(TENANT, TEAM, PROJECT, 90L, new UpdateMilestoneRequest(null, null)));
    }

    @Test
    void updateMilestone_unknownLane_throwsLaneNotFound() {
        final Task existing = milestone(84L, null, 0, "Launch", LocalDate.of(2026, 3, 1));
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(84L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(eq(999L), eq(PROJECT), eq(TENANT), eq(TEAM)))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(LaneNotFoundException.class).isThrownBy(() ->
                service.updateMilestone(TENANT, TEAM, PROJECT, 84L, new UpdateMilestoneRequest(null, 999L)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    void updateMilestone_dateOutOfOtherTasksBounds_throwsOutOfBounds() {
        final Task existing = milestone(85L, null, 0, "Launch", LocalDate.of(2026, 3, 1));
        final Task sibling = datedTask(21L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(85L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(existing, sibling));

        final InvalidMilestoneDateException ex = Assertions.assertThrows(InvalidMilestoneDateException.class,
                () -> service.updateMilestone(TENANT, TEAM, PROJECT, 85L,
                        new UpdateMilestoneRequest(LocalDate.of(2027, 6, 1), null)));

        assertThat(ex.code()).isEqualTo(InvalidMilestoneDateException.CODE_OUT_OF_BOUNDS);
        verify(taskRepository, never()).save(any());
    }

    // ---- scale (US22.3.2) --------------------------------------------------------------------------

    private static Task initiativeWith(final long id, final long laneId, final TemporalPrecision precision,
            final LocalDate start, final LocalDate end, final Horizon horizon) {
        final Task task = new Task(TENANT, TEAM, PROJECT, 0, "I", NodeKind.LEAF, Boolean.TRUE, precision, 0);
        task.setLaneId(laneId);
        task.setFuzzyPeriodStart(start);
        task.setFuzzyPeriodEnd(end);
        task.setHorizon(horizon);
        setId(task, id);
        return task;
    }

    @Test
    void getScale_noExplicitSetting_derivesMacroDefaultFromProfile() {
        // Project seeded in setUp has a null default_temporal_precision; profile altitude = MACRO.
        final RoadmapScaleResponse response = service.getScale(TENANT, TEAM, PROJECT);

        assertThat(response.scale()).isEqualTo(TemporalPrecision.QUARTER);
        assertThat(response.explicit()).isFalse();
    }

    @Test
    void getScale_detailProfile_derivesDayDefault() {
        when(profileResolver.resolveProfile(TENANT)).thenReturn(new DefaultOrganizationProfile(
                Altitude.DETAIL, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, java.util.Set.of("roadmap")));

        final RoadmapScaleResponse response = service.getScale(TENANT, TEAM, PROJECT);

        assertThat(response.scale()).isEqualTo(TemporalPrecision.DAY);
        assertThat(response.explicit()).isFalse();
    }

    @Test
    void getScale_explicitSetting_isReturnedAsExplicit() {
        final Project project = new Project(null, TENANT, TEAM, "P", Instant.now());
        project.setDefaultTemporalPrecision(TemporalPrecision.SEMESTER);
        when(projectRepository.findByIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(Optional.of(project));

        final RoadmapScaleResponse response = service.getScale(TENANT, TEAM, PROJECT);

        assertThat(response.scale()).isEqualTo(TemporalPrecision.SEMESTER);
        assertThat(response.explicit()).isTrue();
    }

    @Test
    void updateScale_persistsSettingWithoutTouchingInitiativePeriods() {
        final Project project = new Project(null, TENANT, TEAM, "P", Instant.now());
        when(projectRepository.findByIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        final RoadmapScaleResponse response = service.updateScale(TENANT, TEAM, PROJECT,
                new UpdateRoadmapScaleRequest(TemporalPrecision.MONTH));

        assertThat(response.scale()).isEqualTo(TemporalPrecision.MONTH);
        assertThat(response.explicit()).isTrue();
        assertThat(project.getDefaultTemporalPrecision()).isEqualTo(TemporalPrecision.MONTH);
        // No initiative row is ever written by a scale change (error AC: no data loss/truncation).
        verify(taskRepository, never()).save(any());
    }

    @Test
    void updateScale_nullScale_throwsInvalidRoadmapScale() {
        assertThatExceptionOfType(InvalidRoadmapScaleException.class).isThrownBy(() ->
                service.updateScale(TENANT, TEAM, PROJECT, new UpdateRoadmapScaleRequest(null)));

        verify(projectRepository, never()).save(any());
    }

    @Test
    void listInitiatives_snapsBarBoundsToQuarterScale() {
        when(laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(lane(1L, "Lane A", 0)));
        // Stored fuzzy period Feb 10 → May 20, no explicit scale ⇒ default QUARTER.
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndLaneIdIsNotNull(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(initiativeWith(40L, 1L, TemporalPrecision.QUARTER,
                        LocalDate.of(2026, 2, 10), LocalDate.of(2026, 5, 20), Horizon.NOW)));

        final InitiativeResponse only = service.listInitiatives(TENANT, TEAM, PROJECT).get(0);

        // Raw period preserved…
        assertThat(only.fuzzyPeriodStart()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(only.fuzzyPeriodEnd()).isEqualTo(LocalDate.of(2026, 5, 20));
        // …bar snapped to Q1 start (Jan 1) and Q2 end (Jun 30).
        assertThat(only.periodBounds().start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(only.periodBounds().end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void listInitiatives_noPeriod_snapsToNullBounds() {
        when(laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(lane(1L, "Lane A", 0)));
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndLaneIdIsNotNull(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(initiativeWith(41L, 1L, TemporalPrecision.QUARTER, null, null, Horizon.NOW)));

        final InitiativeResponse only = service.listInitiatives(TENANT, TEAM, PROJECT).get(0);

        assertThat(only.periodBounds().start()).isNull();
        assertThat(only.periodBounds().end()).isNull();
    }

    // ---- Now / Next / Later (US22.3.3) -------------------------------------------------------------

    @Test
    void listHorizonView_groupsInitiativesIntoOrderedBuckets() {
        when(laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(lane(1L, "Lane A", 0)));
        when(taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndLaneIdIsNotNull(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(
                        initiativeWith(50L, 1L, TemporalPrecision.QUARTER, null, null, Horizon.LATER),
                        initiativeWith(51L, 1L, TemporalPrecision.QUARTER, null, null, Horizon.NOW),
                        initiativeWith(52L, 1L, TemporalPrecision.QUARTER, null, null, null)));

        final HorizonViewResponse view = service.listHorizonView(TENANT, TEAM, PROJECT);

        assertThat(view.buckets()).extracting(HorizonBucketResponse::horizon)
                .containsExactly(Horizon.NOW, Horizon.NEXT, Horizon.LATER);
        assertThat(view.buckets().get(0).initiatives()).extracting(InitiativeResponse::id).containsExactly(51L);
        assertThat(view.buckets().get(1).initiatives()).isEmpty();
        assertThat(view.buckets().get(2).initiatives()).extracting(InitiativeResponse::id).containsExactly(50L);
        // A pre-existing initiative with no horizon is surfaced, never dropped.
        assertThat(view.unbucketed()).extracting(InitiativeResponse::id).containsExactly(52L);
    }

    @Test
    void updateHorizon_movesBucketAndBumpsRevision() {
        final Task existing = initiativeWith(60L, 1L, TemporalPrecision.QUARTER, null, null, Horizon.NOW);
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(60L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final InitiativeResponse response = service.updateHorizon(TENANT, TEAM, PROJECT, 60L,
                new UpdateInitiativeHorizonRequest(Horizon.LATER));

        assertThat(response.horizon()).isEqualTo(Horizon.LATER);
        assertThat(response.revision()).isEqualTo(1);
    }

    @Test
    void updateHorizon_sameBucket_isNoOpAndRevisionUnchanged() {
        final Task existing = initiativeWith(61L, 1L, TemporalPrecision.QUARTER, null, null, Horizon.NEXT);
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(61L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final InitiativeResponse response = service.updateHorizon(TENANT, TEAM, PROJECT, 61L,
                new UpdateInitiativeHorizonRequest(Horizon.NEXT));

        assertThat(response.horizon()).isEqualTo(Horizon.NEXT);
        assertThat(response.revision()).isZero();
    }

    @Test
    void updateHorizon_nullHorizon_throwsInvalidHorizon() {
        final Task existing = initiativeWith(62L, 1L, TemporalPrecision.QUARTER, null, null, Horizon.NOW);
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(62L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(existing));

        assertThatExceptionOfType(InvalidHorizonException.class).isThrownBy(() ->
                service.updateHorizon(TENANT, TEAM, PROJECT, 62L, new UpdateInitiativeHorizonRequest(null)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    void updateHorizon_unknownInitiative_throwsInitiativeNotFound() {
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(999L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(InitiativeNotFoundException.class).isThrownBy(() ->
                service.updateHorizon(TENANT, TEAM, PROJECT, 999L, new UpdateInitiativeHorizonRequest(Horizon.NOW)));
    }

    @Test
    void updateHorizon_taskWithoutLane_isNotAnInitiative_throwsInitiativeNotFound() {
        final Task plainGanttTask = new Task(TENANT, TEAM, PROJECT, 0, "Detail", NodeKind.LEAF, Boolean.FALSE,
                TemporalPrecision.DAY, 0);
        setId(plainGanttTask, 63L);
        when(taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(63L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(plainGanttTask));

        assertThatExceptionOfType(InitiativeNotFoundException.class).isThrownBy(() ->
                service.updateHorizon(TENANT, TEAM, PROJECT, 63L, new UpdateInitiativeHorizonRequest(Horizon.NOW)));
    }
}

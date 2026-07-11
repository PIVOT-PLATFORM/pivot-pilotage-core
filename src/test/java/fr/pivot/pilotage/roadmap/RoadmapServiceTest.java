package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
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

    private RoadmapService service;

    @BeforeEach
    void setUp() {
        service = new RoadmapService(projectRepository, laneRepository, taskRepository);
        lenient().when(projectRepository.findByIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(new Project(null, TENANT, TEAM, "P", Instant.now())));
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
                new CreateInitiativeRequest("Initiative", 1L, null, null, null));

        assertThat(response.id()).isEqualTo(50L);
        assertThat(response.laneId()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Initiative");
        assertThat(response.fuzzyPeriodStart()).isNull();
        assertThat(response.fuzzyPeriodEnd()).isNull();
        assertThat(response.temporalPrecision()).isEqualTo(TemporalPrecision.QUARTER);
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
                new CreateInitiativeRequest("Q1 push", 1L, start, end, TemporalPrecision.MONTH));

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
                        TENANT, TEAM, PROJECT, new CreateInitiativeRequest("X", 999L, null, null, null)));
        assertThat(ex.code()).isEqualTo(LaneNotFoundException.CODE_NOT_FOUND);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void createInitiative_noLaneIdAtAll_throwsLaneNotFoundMissing() {
        final LaneNotFoundException ex = Assertions.assertThrows(
                LaneNotFoundException.class, () -> service.createInitiative(
                        TENANT, TEAM, PROJECT, new CreateInitiativeRequest("X", null, null, null, null)));

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
                        new CreateInitiativeRequest("X", 1L, LocalDate.of(2026, 1, 1), null, null)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    void createInitiative_endBeforeStart_throwsInvalidPeriod() {
        when(laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(lane(1L, "Lane A", 0)));

        assertThatExceptionOfType(InvalidInitiativePeriodException.class).isThrownBy(
                () -> service.createInitiative(TENANT, TEAM, PROJECT, new CreateInitiativeRequest(
                        "X", 1L, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1), null)));

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
}

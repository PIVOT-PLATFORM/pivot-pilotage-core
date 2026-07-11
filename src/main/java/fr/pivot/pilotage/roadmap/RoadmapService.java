package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing {@link RoadmapController} (US22.3.1 — "Créer une roadmap rapide").
 *
 * <p>Owns the two operations the backlog Gate 1 file left the technical design to this US:
 * <ul>
 *   <li>the <strong>lane</strong> concept (theme/team/objective grouping, {@link Lane}), and</li>
 *   <li>the mapping of an "initiative" onto the existing temporal graph — an initiative is a
 *       plain {@code fr.pivot.pilotage.schedule.Task} (leaf, shared in the roadmap, assigned to a
 *       lane), never a separate entity, per the backlog note.</li>
 * </ul>
 *
 * <p><strong>US22.3.4 — strategic milestones</strong> extend this service with the same pattern: a
 * milestone is a plain {@code Task} with {@code node_kind=MILESTONE} (EN22.1a), never a new entity
 * (backlog note: "un seul enregistrement, deux rendus — barre roadmap vs. losange Gantt"). See the
 * {@code // ---- milestones} section below and {@link InvalidMilestoneDateException}'s Javadoc for
 * the documented PO Agent clarification of the "hors des bornes du projet" AC.
 *
 * <p><strong>REST-era tenant/team scoping.</strong> Per CLAUDE.md §gap and TODO-SETUP §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId}
 * are explicit arguments throughout (as with every other pre-starter controller in this repo) —
 * never taken from a request body. Every method resolves the target project first via
 * {@link #requireProject(long, long, long)}, a single tenant+team-scoped repository lookup that
 * collapses "unknown tenant", "unknown team", "cross-team project" and "unknown project" into one
 * non-disclosing {@link ProjectNotFoundException} (404) — see that exception's Javadoc for why a
 * single check suffices here (unlike EN18.10's split tenant/team checks).
 */
@Service
public class RoadmapService {

    /**
     * Default temporal precision for a newly created initiative when the request omits one —
     * roadmap-rapide is a macro tool; the fine-grained scale UI (US22.3.2) may later send an
     * explicit value without any contract change here.
     */
    private static final TemporalPrecision DEFAULT_TEMPORAL_PRECISION = TemporalPrecision.QUARTER;

    /** Initial revision of a newly created initiative. */
    private static final int INITIAL_REVISION = 0;

    private final ProjectRepository projectRepository;
    private final LaneRepository laneRepository;
    private final TaskRepository taskRepository;

    /**
     * Constructs the service.
     *
     * @param projectRepository tenant/team-scoped project repository (EN18.1)
     * @param laneRepository    lane repository (US22.3.1)
     * @param taskRepository    tenant/team-scoped task (temporal graph) repository (EN22.1a)
     */
    public RoadmapService(final ProjectRepository projectRepository, final LaneRepository laneRepository,
            final TaskRepository taskRepository) {
        this.projectRepository = projectRepository;
        this.laneRepository = laneRepository;
        this.taskRepository = taskRepository;
    }

    // ---- lanes ---------------------------------------------------------------------------------

    /**
     * Lists a project's lanes, ordered by display position.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the lanes, ordered by position (possibly empty)
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public List<LaneResponse> listLanes(final long tenantId, final long teamId, final long projectId) {
        requireProject(tenantId, teamId, projectId);
        return laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(
                        projectId, tenantId, teamId)
                .stream()
                .map(LaneResponse::from)
                .toList();
    }

    /**
     * Creates a new lane on a project's roadmap-rapide view, appended after the project's
     * existing lanes.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the lane creation payload
     * @return the created lane
     * @throws ProjectNotFoundException      if the project is not visible to the tenant/team
     * @throws DuplicateLaneNameException if a lane with the same label already exists on the project
     */
    @Transactional
    public LaneResponse createLane(final long tenantId, final long teamId, final long projectId,
            final CreateLaneRequest request) {
        requireProject(tenantId, teamId, projectId);
        if (laneRepository.existsByProjectIdAndTenantIdAndTeamIdAndNameIgnoreCase(
                projectId, tenantId, teamId, request.name())) {
            throw new DuplicateLaneNameException(request.name(), projectId);
        }

        final int position = (int) laneRepository.countByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId);
        final Lane lane = new Lane(tenantId, teamId, projectId, request.name(), position);
        return LaneResponse.from(laneRepository.save(lane));
    }

    // ---- initiatives ----------------------------------------------------------------------------

    /**
     * Lists a project's roadmap-rapide initiatives, ordered by their lane's display position then
     * by their own position within that lane.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the initiatives, ordered (possibly empty)
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public List<InitiativeResponse> listInitiatives(final long tenantId, final long teamId, final long projectId) {
        requireProject(tenantId, teamId, projectId);

        final Map<Long, Integer> lanePositions = new HashMap<>();
        for (final Lane lane : laneRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByPositionAscIdAsc(
                projectId, tenantId, teamId)) {
            lanePositions.put(lane.getId(), lane.getPosition());
        }

        final List<Task> initiatives = new ArrayList<>(
                taskRepository.findAllByProjectIdAndTenantIdAndTeamIdAndLaneIdIsNotNull(
                        projectId, tenantId, teamId));
        initiatives.sort(Comparator
                .<Task>comparingInt(t -> lanePositions.getOrDefault(t.getLaneId(), Integer.MAX_VALUE))
                .thenComparingInt(t -> t.getPosition() == null ? 0 : t.getPosition())
                .thenComparing(Task::getId));

        return initiatives.stream().map(InitiativeResponse::from).toList();
    }

    /**
     * Creates a new initiative (a leaf {@code Task}, shared in the roadmap) posed on a lane.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the initiative creation payload
     * @return the created initiative
     * @throws ProjectNotFoundException          if the project is not visible to the tenant/team
     * @throws LaneNotFoundException              if {@code request.laneId()} is missing or does not
     *                                            resolve on this project
     * @throws InvalidInitiativePeriodException if the supplied period is inconsistent
     */
    @Transactional
    public InitiativeResponse createInitiative(final long tenantId, final long teamId, final long projectId,
            final CreateInitiativeRequest request) {
        requireProject(tenantId, teamId, projectId);
        requireLane(tenantId, teamId, projectId, request.laneId());
        validatePeriod(request.fuzzyPeriodStart(), request.fuzzyPeriodEnd());

        final int position = (int) taskRepository.countByLaneIdAndTenantIdAndTeamId(
                request.laneId(), tenantId, teamId);
        final TemporalPrecision precision = request.temporalPrecision() != null
                ? request.temporalPrecision() : DEFAULT_TEMPORAL_PRECISION;

        final Task task = new Task(tenantId, teamId, projectId, position, request.name(), NodeKind.LEAF,
                Boolean.TRUE, precision, INITIAL_REVISION);
        task.setLaneId(request.laneId());
        task.setFuzzyPeriodStart(request.fuzzyPeriodStart());
        task.setFuzzyPeriodEnd(request.fuzzyPeriodEnd());

        return InitiativeResponse.from(taskRepository.save(task));
    }

    /**
     * Moves and/or resizes an initiative, and optionally reassigns it to a different lane.
     *
     * @param tenantId     the requesting tenant's {@code public.tenants.id}
     * @param teamId       the requesting team's {@code public.teams.id}
     * @param projectId    the project id
     * @param initiativeId the initiative (task) id
     * @param request      the placement update payload
     * @return the updated initiative
     * @throws ProjectNotFoundException          if the project is not visible to the tenant/team
     * @throws InitiativeNotFoundException      if the initiative does not resolve on this project
     * @throws LaneNotFoundException              if a supplied {@code laneId} does not resolve on
     *                                            this project
     * @throws InvalidInitiativePeriodException if the supplied period is inconsistent
     */
    @Transactional
    public InitiativeResponse updatePlacement(final long tenantId, final long teamId, final long projectId,
            final long initiativeId, final UpdateInitiativePlacementRequest request) {
        requireProject(tenantId, teamId, projectId);
        final Task task = taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(
                        initiativeId, projectId, tenantId, teamId)
                .filter(t -> t.getLaneId() != null)
                .orElseThrow(() -> new InitiativeNotFoundException(initiativeId, projectId));

        boolean changed = false;
        if (request.laneId() != null) {
            requireLane(tenantId, teamId, projectId, request.laneId());
            task.setLaneId(request.laneId());
            changed = true;
        }
        if (request.fuzzyPeriodStart() != null || request.fuzzyPeriodEnd() != null) {
            validatePeriod(request.fuzzyPeriodStart(), request.fuzzyPeriodEnd());
            task.setFuzzyPeriodStart(request.fuzzyPeriodStart());
            task.setFuzzyPeriodEnd(request.fuzzyPeriodEnd());
            changed = true;
        }
        if (changed) {
            task.setRevision((task.getRevision() == null ? 0 : task.getRevision()) + 1);
        }

        return InitiativeResponse.from(taskRepository.save(task));
    }

    // ---- milestones (US22.3.4) ------------------------------------------------------------------

    /**
     * Lists a project's strategic milestones, ordered by date (earliest first, undated milestones
     * last), then by id for a stable tie-break.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the milestones, ordered (possibly empty)
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public List<MilestoneResponse> listMilestones(final long tenantId, final long teamId, final long projectId) {
        requireProject(tenantId, teamId, projectId);
        return taskRepository
                .findAllByProjectIdAndTenantIdAndTeamIdAndNodeKind(projectId, tenantId, teamId, NodeKind.MILESTONE)
                .stream()
                .sorted(Comparator.comparing(Task::getFuzzyPeriodStart, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::getId))
                .map(MilestoneResponse::from)
                .toList();
    }

    /**
     * Creates a new strategic milestone (a {@code Task} with {@code node_kind=MILESTONE}, shared in
     * the roadmap), optionally pinned to a lane.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the milestone creation payload
     * @return the created milestone
     * @throws ProjectNotFoundException        if the project is not visible to the tenant/team
     * @throws LaneNotFoundException           if a supplied {@code laneId} does not resolve on
     *                                         this project (never thrown when {@code laneId} is
     *                                         {@code null} — unlike an initiative, a milestone's
     *                                         lane is optional)
     * @throws InvalidMilestoneDateException if the date is missing or outside the project's
     *                                         derived bounds
     */
    @Transactional
    public MilestoneResponse createMilestone(final long tenantId, final long teamId, final long projectId,
            final CreateMilestoneRequest request) {
        requireProject(tenantId, teamId, projectId);
        if (request.laneId() != null) {
            requireLane(tenantId, teamId, projectId, request.laneId());
        }
        if (request.date() == null) {
            throw InvalidMilestoneDateException.missing(projectId);
        }
        requireWithinProjectBounds(tenantId, teamId, projectId, request.date(), null);

        final int position = (int) taskRepository.countByProjectIdAndTenantIdAndTeamIdAndNodeKind(
                projectId, tenantId, teamId, NodeKind.MILESTONE);
        final Task task = new Task(tenantId, teamId, projectId, position, request.name(), NodeKind.MILESTONE,
                Boolean.TRUE, TemporalPrecision.DAY, INITIAL_REVISION);
        task.setLaneId(request.laneId());
        task.setDurationMinutes(0);
        applyMilestoneDate(task, request.date());

        return MilestoneResponse.from(taskRepository.save(task));
    }

    /**
     * Moves a milestone to a new date and/or reassigns it to a different lane. A date change here
     * is the single write path satisfying the AC "given a milestone, when its date changes... then
     * the roadmap reflects the change" — both views read the same row, so no propagation step is
     * needed beyond this update.
     *
     * @param tenantId    the requesting tenant's {@code public.tenants.id}
     * @param teamId      the requesting team's {@code public.teams.id}
     * @param projectId   the project id
     * @param milestoneId the milestone (task) id
     * @param request     the update payload
     * @return the updated milestone
     * @throws ProjectNotFoundException        if the project is not visible to the tenant/team
     * @throws MilestoneNotFoundException     if the milestone does not resolve on this project
     * @throws LaneNotFoundException           if a supplied {@code laneId} does not resolve on
     *                                         this project
     * @throws InvalidMilestoneDateException if a supplied date is outside the project's derived
     *                                         bounds
     */
    @Transactional
    public MilestoneResponse updateMilestone(final long tenantId, final long teamId, final long projectId,
            final long milestoneId, final UpdateMilestoneRequest request) {
        requireProject(tenantId, teamId, projectId);
        final Task task = taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(
                        milestoneId, projectId, tenantId, teamId)
                .filter(t -> t.getNodeKind() == NodeKind.MILESTONE)
                .orElseThrow(() -> new MilestoneNotFoundException(milestoneId, projectId));

        boolean changed = false;
        if (request.laneId() != null) {
            requireLane(tenantId, teamId, projectId, request.laneId());
            task.setLaneId(request.laneId());
            changed = true;
        }
        if (request.date() != null) {
            requireWithinProjectBounds(tenantId, teamId, projectId, request.date(), task.getId());
            applyMilestoneDate(task, request.date());
            changed = true;
        }
        if (changed) {
            task.setRevision((task.getRevision() == null ? 0 : task.getRevision()) + 1);
        }

        return MilestoneResponse.from(taskRepository.save(task));
    }

    /**
     * Writes a milestone's single date onto <em>both</em> temporal representations carried by the
     * same {@code task} row — the fuzzy period (roadmap view) and the precise Gantt bounds — so
     * either view, present or future, resolves the identical date with no service-side conversion
     * at read time (see {@link MilestoneResponse}'s Javadoc).
     *
     * @param task the milestone task being created or moved
     * @param date the date to apply
     */
    private static void applyMilestoneDate(final Task task, final LocalDate date) {
        task.setFuzzyPeriodStart(date);
        task.setFuzzyPeriodEnd(date);
        final Instant atMidnightUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        task.setStartDate(atMidnightUtc);
        task.setFinishDate(atMidnightUtc);
    }

    /**
     * Validates a milestone date against the project's <strong>derived</strong> bounds — see
     * {@link InvalidMilestoneDateException}'s Javadoc for why this is computed rather than read
     * from a dedicated column. The bounds are the envelope (earliest effective start, latest
     * effective end) of every other task already scheduled on the project; a project with no other
     * dated task imposes no bound at all.
     *
     * @param tenantId        the requesting tenant's {@code public.tenants.id}
     * @param teamId          the requesting team's {@code public.teams.id}
     * @param projectId       the project id
     * @param date            the candidate milestone date
     * @param excludedTaskId  the milestone's own task id to exclude from the envelope when moving
     *                        an already-placed milestone (so it is never bounded by itself), or
     *                        {@code null} on creation
     * @throws InvalidMilestoneDateException if the date falls outside the derived bounds
     */
    private void requireWithinProjectBounds(final long tenantId, final long teamId, final long projectId,
            final LocalDate date, final Long excludedTaskId) {
        LocalDate lowerBound = null;
        LocalDate upperBound = null;
        for (final Task other : taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)) {
            if (excludedTaskId != null && excludedTaskId.equals(other.getId())) {
                continue;
            }
            final LocalDate start = effectiveDate(other, true);
            final LocalDate end = effectiveDate(other, false);
            if (start != null && (lowerBound == null || start.isBefore(lowerBound))) {
                lowerBound = start;
            }
            if (end != null && (upperBound == null || end.isAfter(upperBound))) {
                upperBound = end;
            }
        }
        final boolean beforeLowerBound = lowerBound != null && date.isBefore(lowerBound);
        final boolean afterUpperBound = upperBound != null && date.isAfter(upperBound);
        if (beforeLowerBound || afterUpperBound) {
            throw InvalidMilestoneDateException.outOfBounds(date, projectId, lowerBound, upperBound);
        }
    }

    /**
     * Resolves a task's effective start or finish date, preferring the fuzzy period bound and
     * falling back to the precise Gantt bound (converted to UTC) when the fuzzy bound is absent.
     *
     * @param task  the task to read
     * @param start {@code true} to resolve the start bound, {@code false} for the finish bound
     * @return the effective date, or {@code null} if neither representation carries one
     */
    private static LocalDate effectiveDate(final Task task, final boolean start) {
        final LocalDate fuzzy = start ? task.getFuzzyPeriodStart() : task.getFuzzyPeriodEnd();
        if (fuzzy != null) {
            return fuzzy;
        }
        final Instant precise = start ? task.getStartDate() : task.getFinishDate();
        return precise != null ? precise.atZone(ZoneOffset.UTC).toLocalDate() : null;
    }

    // ---- shared guards --------------------------------------------------------------------------

    /**
     * Resolves the target project within the tenant/team boundary — the single isolation check
     * shared by every roadmap-rapide operation.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the resolved project
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    private Project requireProject(final long tenantId, final long teamId, final long projectId) {
        return projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, tenantId, teamId));
    }

    /**
     * Verifies a lane id resolves on the given project/tenant/team — {@code null} is rejected as
     * "no lane at all" (AC: "a message indicates a lane is required"), distinct from a non-null but
     * unresolvable id ("invalid lane").
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param laneId    the lane id to verify, or {@code null}
     * @throws LaneNotFoundException if no lane id was supplied, or it does not resolve on this
     *                                 project
     */
    private void requireLane(final long tenantId, final long teamId, final long projectId, final Long laneId) {
        if (laneId == null) {
            throw LaneNotFoundException.missing(projectId);
        }
        laneRepository.findByIdAndProjectIdAndTenantIdAndTeamId(laneId, projectId, tenantId, teamId)
                .orElseThrow(() -> LaneNotFoundException.invalid(laneId, projectId));
    }

    /**
     * Validates an approximate period: both bounds must be supplied together (never a lone
     * bound), and the end must not precede the start.
     *
     * @param start the period lower bound, or {@code null}
     * @param end   the period upper bound, or {@code null}
     * @throws InvalidInitiativePeriodException if the period is inconsistent
     */
    private static void validatePeriod(final LocalDate start, final LocalDate end) {
        if ((start == null) != (end == null)) {
            throw new InvalidInitiativePeriodException(
                    "fuzzyPeriodStart and fuzzyPeriodEnd must be supplied together");
        }
        if (start != null && end.isBefore(start)) {
            throw new InvalidInitiativePeriodException("fuzzyPeriodEnd must not precede fuzzyPeriodStart");
        }
    }
}

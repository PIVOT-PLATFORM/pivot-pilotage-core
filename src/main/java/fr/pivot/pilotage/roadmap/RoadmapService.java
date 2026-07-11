package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.profile.OrganizationProfileResolver;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.projection.Altitude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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

    /**
     * Effective roadmap scale mapped from a {@link Altitude#MACRO} default profile (EN18.10) — the
     * coarse, strategic grain the roadmap-rapide macro view opens on. Kept identical to
     * {@link #DEFAULT_TEMPORAL_PRECISION} so a roadmap with no explicit scale set renders exactly
     * as its freshly created initiatives already do.
     */
    private static final TemporalPrecision MACRO_DEFAULT_SCALE = TemporalPrecision.QUARTER;

    /**
     * Effective roadmap scale mapped from a {@link Altitude#DETAIL} default profile (EN18.10) — the
     * fine, day grain a detail-oriented tenant opens on. The profile carries a view
     * <em>altitude</em> (MACRO/DETAIL), not a temporal grain; this is the documented mapping the
     * roadmap consumes rather than re-deriving a scale of its own.
     */
    private static final TemporalPrecision DETAIL_DEFAULT_SCALE = TemporalPrecision.DAY;

    /** Default Now/Next/Later bucket of a freshly created initiative (US22.3.3). */
    private static final Horizon DEFAULT_HORIZON = Horizon.NOW;

    /** Initial revision of a newly created initiative. */
    private static final int INITIAL_REVISION = 0;

    private final ProjectRepository projectRepository;
    private final LaneRepository laneRepository;
    private final TaskRepository taskRepository;
    private final OrganizationProfileResolver profileResolver;

    /**
     * Constructs the service.
     *
     * @param projectRepository tenant/team-scoped project repository (EN18.1)
     * @param laneRepository    lane repository (US22.3.1)
     * @param taskRepository    tenant/team-scoped task (temporal graph) repository (EN22.1a)
     * @param profileResolver   default organization-profile resolver (EN18.10) — consumed to derive
     *                          the default roadmap scale when a roadmap carries no explicit setting
     *                          (US22.3.2), never re-implemented here
     */
    public RoadmapService(final ProjectRepository projectRepository, final LaneRepository laneRepository,
            final TaskRepository taskRepository, final OrganizationProfileResolver profileResolver) {
        this.projectRepository = projectRepository;
        this.laneRepository = laneRepository;
        this.taskRepository = taskRepository;
        this.profileResolver = profileResolver;
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
        final Project project = requireProject(tenantId, teamId, projectId);
        final TemporalPrecision scale = effectiveScale(tenantId, project);

        final List<Task> initiatives = orderedInitiatives(tenantId, teamId, projectId);
        return initiatives.stream().map(task -> InitiativeResponse.from(task, scale)).toList();
    }

    /**
     * Loads a project's roadmap-rapide initiatives (lane-assigned tasks) in the canonical roadmap
     * order — by lane display position, then by position within the lane, then by id. Shared by the
     * temporal listing ({@link #listInitiatives}) and the Now/Next/Later view
     * ({@link #listHorizonView}) so both render the same set in the same relative order (US22.3.3:
     * "même jeu d'initiatives, changement de rendu uniquement").
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the ordered initiatives (possibly empty)
     */
    private List<Task> orderedInitiatives(final long tenantId, final long teamId, final long projectId) {
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
        return initiatives;
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
        final Project project = requireProject(tenantId, teamId, projectId);
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
        task.setHorizon(request.horizon() != null ? request.horizon() : DEFAULT_HORIZON);

        return InitiativeResponse.from(taskRepository.save(task), effectiveScale(tenantId, project));
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
        final Project project = requireProject(tenantId, teamId, projectId);
        final Task task = requireInitiative(tenantId, teamId, projectId, initiativeId);

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

        return InitiativeResponse.from(taskRepository.save(task), effectiveScale(tenantId, project));
    }

    // ---- scale (US22.3.2) -----------------------------------------------------------------------

    /**
     * Reads a roadmap's fuzzy time scale (US22.3.2) — the per-roadmap view setting stored on
     * {@code pilotage.project.default_temporal_precision}, or, when unset, the scale derived from
     * the tenant's default profile (EN18.10).
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the effective scale and whether it is an explicit per-roadmap setting
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public RoadmapScaleResponse getScale(final long tenantId, final long teamId, final long projectId) {
        final Project project = requireProject(tenantId, teamId, projectId);
        final TemporalPrecision explicit = project.getDefaultTemporalPrecision();
        return new RoadmapScaleResponse(explicit != null ? explicit : defaultScale(tenantId), explicit != null);
    }

    /**
     * Sets a roadmap's fuzzy time scale (US22.3.2). A pure view setting: it writes only
     * {@code pilotage.project.default_temporal_precision} and never touches any initiative's stored
     * period, so switching scale "sans supprimer ni tronquer les données de période existantes"
     * (error AC) is structurally guaranteed — a re-listed initiative simply snaps to the new scale.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the scale update payload
     * @return the effective scale after the update (always explicit)
     * @throws ProjectNotFoundException    if the project is not visible to the tenant/team
     * @throws InvalidRoadmapScaleException if no scale was supplied
     */
    @Transactional
    public RoadmapScaleResponse updateScale(final long tenantId, final long teamId, final long projectId,
            final UpdateRoadmapScaleRequest request) {
        final Project project = requireProject(tenantId, teamId, projectId);
        if (request.scale() == null) {
            throw new InvalidRoadmapScaleException(projectId);
        }
        project.setDefaultTemporalPrecision(request.scale());
        projectRepository.save(project);
        return new RoadmapScaleResponse(request.scale(), true);
    }

    /**
     * Resolves the effective scale for a project — its explicit per-roadmap setting when present,
     * otherwise the tenant's profile-derived default (EN18.10).
     *
     * @param tenantId the requesting tenant's {@code public.tenants.id}
     * @param project  the resolved project
     * @return the effective scale (never {@code null})
     */
    private TemporalPrecision effectiveScale(final long tenantId, final Project project) {
        final TemporalPrecision explicit = project.getDefaultTemporalPrecision();
        return explicit != null ? explicit : defaultScale(tenantId);
    }

    /**
     * Derives the default roadmap scale from the tenant's default profile (EN18.10) — consumes
     * {@code resolveProfile(tenant).altitude()} and maps the view altitude (MACRO/DETAIL) onto a
     * temporal grain. The profile is the single source of the default; this service never invents a
     * scale of its own.
     *
     * @param tenantId the requesting tenant's {@code public.tenants.id}
     * @return the default scale for the tenant
     */
    private TemporalPrecision defaultScale(final long tenantId) {
        final Altitude altitude = profileResolver.resolveProfile(tenantId).altitude();
        return altitude == Altitude.DETAIL ? DETAIL_DEFAULT_SCALE : MACRO_DEFAULT_SCALE;
    }

    // ---- Now / Next / Later (US22.3.3) ----------------------------------------------------------

    /**
     * Groups a project's roadmap-rapide initiatives into the Now/Next/Later buckets (US22.3.3) —
     * an alternative projection over the same initiatives as {@link #listInitiatives}, with no
     * temporal axis. The three concrete buckets are always present in {@code NOW}, {@code NEXT},
     * {@code LATER} order (each possibly empty); initiatives with no horizon yet are surfaced
     * separately (never dropped). Intra-bucket order follows the canonical roadmap order.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the Now/Next/Later grouped view
     * @throws ProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public HorizonViewResponse listHorizonView(final long tenantId, final long teamId, final long projectId) {
        final Project project = requireProject(tenantId, teamId, projectId);
        final TemporalPrecision scale = effectiveScale(tenantId, project);

        final Map<Horizon, List<InitiativeResponse>> byBucket = new EnumMap<>(Horizon.class);
        for (final Horizon horizon : Horizon.values()) {
            byBucket.put(horizon, new ArrayList<>());
        }
        final List<InitiativeResponse> unbucketed = new ArrayList<>();

        for (final Task task : orderedInitiatives(tenantId, teamId, projectId)) {
            final InitiativeResponse response = InitiativeResponse.from(task, scale);
            if (task.getHorizon() == null) {
                unbucketed.add(response);
            } else {
                byBucket.get(task.getHorizon()).add(response);
            }
        }

        final List<HorizonBucketResponse> buckets = new ArrayList<>();
        for (final Horizon horizon : Horizon.values()) {
            buckets.add(new HorizonBucketResponse(horizon, byBucket.get(horizon)));
        }
        return new HorizonViewResponse(buckets, unbucketed);
    }

    /**
     * Moves an initiative to a different Now/Next/Later bucket (US22.3.3). The single write path for
     * the "je la glisse d'un bucket à l'autre" AC — both the temporal and the Now/Next/Later views
     * read the same {@code task.horizon} column, so no propagation is needed beyond this update.
     *
     * @param tenantId     the requesting tenant's {@code public.tenants.id}
     * @param teamId       the requesting team's {@code public.teams.id}
     * @param projectId    the project id
     * @param initiativeId the initiative (task) id
     * @param request      the horizon update payload
     * @return the updated initiative
     * @throws ProjectNotFoundException     if the project is not visible to the tenant/team
     * @throws InitiativeNotFoundException  if the initiative does not resolve on this project
     * @throws InvalidHorizonException      if no target horizon was supplied
     */
    @Transactional
    public InitiativeResponse updateHorizon(final long tenantId, final long teamId, final long projectId,
            final long initiativeId, final UpdateInitiativeHorizonRequest request) {
        final Project project = requireProject(tenantId, teamId, projectId);
        final Task task = requireInitiative(tenantId, teamId, projectId, initiativeId);
        if (request.horizon() == null) {
            throw new InvalidHorizonException(initiativeId);
        }

        if (request.horizon() != task.getHorizon()) {
            task.setHorizon(request.horizon());
            task.setRevision((task.getRevision() == null ? 0 : task.getRevision()) + 1);
        }

        return InitiativeResponse.from(taskRepository.save(task), effectiveScale(tenantId, project));
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
     * Resolves a roadmap-rapide initiative (a lane-assigned task) within the project/tenant/team
     * boundary — the shared lookup for the placement (US22.3.1) and horizon (US22.3.3) write paths.
     * A task with no {@code lane_id} is a plain Gantt task, not an initiative this endpoint exposes,
     * and is treated as not found (same non-disclosure posture).
     *
     * @param tenantId     the requesting tenant's {@code public.tenants.id}
     * @param teamId       the requesting team's {@code public.teams.id}
     * @param projectId    the project id
     * @param initiativeId the initiative (task) id
     * @return the resolved initiative task
     * @throws InitiativeNotFoundException if the initiative does not resolve as a roadmap-rapide
     *                                     initiative on this project
     */
    private Task requireInitiative(final long tenantId, final long teamId, final long projectId,
            final long initiativeId) {
        return taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(initiativeId, projectId, tenantId, teamId)
                .filter(t -> t.getLaneId() != null)
                .orElseThrow(() -> new InitiativeNotFoundException(initiativeId, projectId));
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

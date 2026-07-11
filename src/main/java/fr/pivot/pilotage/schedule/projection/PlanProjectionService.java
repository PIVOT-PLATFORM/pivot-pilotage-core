package fr.pivot.pilotage.schedule.projection;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.Horizon;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskDependency;
import fr.pivot.pilotage.schedule.TaskDependencyRepository;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped projection service (EN22.1c, frozen contract §c) that derives the roadmap (macro)
 * and Gantt (detail) views from the <strong>same</strong> persisted temporal graph — never a second
 * store. Two views = two projections of one query (ADR-010): the macro view reads the fuzzy-period
 * bounds and shared milestones and folds summary rollups; the detail view reads the precise dates,
 * dependency edges and the engine-derived critical flag. A shared milestone appears in both with the
 * same node id (non-divergence invariant).
 *
 * <p><strong>REST deferred.</strong> Per CLAUDE.md §gap and TODO-SETUP §5, {@code
 * pivot-core-starter} (TenantContext/auth) is not published, so this enabler ships the service layer
 * only: {@code tenantId} is an explicit argument (as with the EN22.1a tenant-scoped repositories),
 * never taken from a body/param. A project owned by another tenant resolves to {@link
 * Optional#empty()} — the caller-facing 404 (non-disclosure) maps at the future controller layer.
 * The endpoint {@code GET /projects/{id}/plan?altitude=…} is deferred to a post-starter US.
 *
 * <p><strong>Default altitude — EN18.10 seam.</strong> When a request carries no explicit altitude,
 * {@link #project(long, long)} consults {@link DefaultAltitudeProvider} (today {@link
 * FixedDefaultAltitudeProvider}, replaced by EN18.10's profile-backed provider). The explicit
 * overload {@link #project(long, long, Altitude, Layout)} takes the altitude directly and never
 * consults the default.
 */
@Service
public class PlanProjectionService {

    private static final MathContext PCT_CONTEXT = new MathContext(6);

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final TaskProgressRepository progressRepository;
    private final AssignmentRepository assignmentRepository;
    private final DefaultAltitudeProvider defaultAltitudeProvider;

    /**
     * Constructs the projection service with its tenant-scoped repositories and the default-altitude
     * seam.
     *
     * @param projectRepository       project repository
     * @param taskRepository          task repository
     * @param dependencyRepository    dependency repository
     * @param progressRepository      task-progress repository (rollup percent complete)
     * @param assignmentRepository    assignment repository (rollup work/cost)
     * @param defaultAltitudeProvider default-altitude seam (EN18.10)
     */
    public PlanProjectionService(final ProjectRepository projectRepository,
            final TaskRepository taskRepository,
            final TaskDependencyRepository dependencyRepository,
            final TaskProgressRepository progressRepository,
            final AssignmentRepository assignmentRepository,
            final DefaultAltitudeProvider defaultAltitudeProvider) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.dependencyRepository = dependencyRepository;
        this.progressRepository = progressRepository;
        this.assignmentRepository = assignmentRepository;
        this.defaultAltitudeProvider = defaultAltitudeProvider;
    }

    /**
     * Projects a project's plan using the tenant's default altitude (EN18.10 seam) and its natural
     * layout ({@link Layout#TIMELINE} for macro, {@link Layout#GANTT} for detail).
     *
     * @param projectId the project id
     * @param tenantId  the owning tenant id (isolation boundary)
     * @return the projected view, or {@link Optional#empty()} if the project is not visible to the
     *         tenant (maps to 404 at the controller layer)
     */
    @Transactional(readOnly = true)
    public Optional<PlanView> project(final long projectId, final long tenantId) {
        final Altitude altitude = defaultAltitudeProvider.defaultAltitude(tenantId);
        final Layout layout = altitude == Altitude.MACRO ? Layout.TIMELINE : Layout.GANTT;
        return project(projectId, tenantId, altitude, layout);
    }

    /**
     * Projects a project's plan at an <strong>explicit</strong> altitude and layout — the default
     * provider is not consulted.
     *
     * @param projectId the project id
     * @param tenantId  the owning tenant id (isolation boundary)
     * @param altitude  the requested altitude (never {@code null})
     * @param layout    the requested layout (never {@code null})
     * @return the projected view, or {@link Optional#empty()} if the project is not visible to the
     *         tenant
     */
    @Transactional(readOnly = true)
    public Optional<PlanView> project(final long projectId, final long tenantId,
            final Altitude altitude, final Layout layout) {
        final Optional<Project> project = projectRepository.findByIdAndTenantId(projectId, tenantId);
        if (project.isEmpty()) {
            return Optional.empty();
        }
        final List<Task> tasks = taskRepository.findAllByProjectIdAndTenantId(projectId, tenantId);
        final Map<Long, SummaryAggregate> aggregates = rollups(tasks, tenantId);

        final List<PlanNodeView> nodes = new ArrayList<>();
        for (final Task t : ordered(tasks)) {
            if (altitude == Altitude.MACRO && !isMacroVisible(t)) {
                continue;
            }
            nodes.add(toNodeView(t, aggregates.get(t.getId())));
        }

        final List<DependencyView> dependencies = altitude == Altitude.DETAIL
                ? edges(tasks, tenantId) : List.of();
        final Map<Horizon, List<PlanNodeView>> buckets = layout == Layout.BUCKETS
                ? bucketByHorizon(nodes) : Map.of();

        return Optional.of(new PlanView(projectId, altitude, layout, nodes, dependencies,
                aggregates, buckets));
    }

    // ---- macro visibility ---------------------------------------------------------------------

    /**
     * A node is projected into the macro (roadmap) view when it is flagged {@code shared_in_roadmap}
     * (this covers the shared milestone) or when it is a high-level container (SUMMARY) — leaves stay
     * out of the roadmap. The shared milestone therefore appears in the detail view (all nodes) and
     * in the macro view (shared) with the same id.
     */
    private static boolean isMacroVisible(final Task t) {
        return Boolean.TRUE.equals(t.getSharedInRoadmap()) || t.getNodeKind() == NodeKind.SUMMARY;
    }

    private static List<Task> ordered(final List<Task> tasks) {
        final List<Task> copy = new ArrayList<>(tasks);
        copy.sort(Comparator.comparingInt((Task t) -> t.getPosition() == null ? 0 : t.getPosition())
                .thenComparing(Task::getId));
        return copy;
    }

    // ---- node view mapping --------------------------------------------------------------------

    private static PlanNodeView toNodeView(final Task t, final SummaryAggregate agg) {
        final boolean aggregated = t.getNodeKind() == NodeKind.SUMMARY && agg != null;
        final Instant start = aggregated ? agg.rollupStart() : t.getStartDate();
        final Instant finish = aggregated ? agg.rollupFinish() : t.getFinishDate();
        final Boolean critical = aggregated ? Boolean.valueOf(agg.critical()) : t.getCritical();
        return new PlanNodeView(t.getId(), t.getParentTaskId(), t.getWbsCode(), t.getName(),
                t.getNodeKind(), Boolean.TRUE.equals(t.getSharedInRoadmap()), t.getHorizon(),
                t.getFuzzyPeriodStart(), t.getFuzzyPeriodEnd(), start, finish, critical,
                aggregated, t.getRevision() == null ? 0 : t.getRevision());
    }

    // ---- dependency edges (detail only) -------------------------------------------------------

    private List<DependencyView> edges(final List<Task> tasks, final long tenantId) {
        final List<DependencyView> edges = new ArrayList<>();
        for (final Task t : tasks) {
            for (final TaskDependency d
                    : dependencyRepository.findAllByPredecessorTaskIdAndTenantId(t.getId(), tenantId)) {
                final long id = d.getId() != null ? d.getId() : 0L;
                final long lag = d.getLagMinutes() != null ? d.getLagMinutes() : 0L;
                edges.add(new DependencyView(id, d.getPredecessorTaskId(), d.getSuccessorTaskId(),
                        d.getLinkType(), lag));
            }
        }
        edges.sort(Comparator.comparingLong(DependencyView::predecessorId)
                .thenComparingLong(DependencyView::successorId));
        return edges;
    }

    // ---- Now/Next/Later buckets ---------------------------------------------------------------

    private static Map<Horizon, List<PlanNodeView>> bucketByHorizon(final List<PlanNodeView> nodes) {
        final Map<Horizon, List<PlanNodeView>> buckets = new EnumMap<>(Horizon.class);
        for (final PlanNodeView n : nodes) {
            if (n.horizon() != null) {
                buckets.computeIfAbsent(n.horizon(), h -> new ArrayList<>()).add(n);
            }
        }
        return buckets;
    }

    // ---- summary rollups (derived, never persisted twice) -------------------------------------

    /**
     * Computes, per SUMMARY node, the rollup of its leaf descendants (start=min, finish=max,
     * work/cost=Σ, %=charge-weighted, critical if ≥1 leaf critical). Purely derived in projection —
     * nothing is written back onto the SUMMARY row.
     */
    private Map<Long, SummaryAggregate> rollups(final List<Task> tasks, final long tenantId) {
        final Map<Long, List<Task>> childrenByParent = new HashMap<>();
        for (final Task t : tasks) {
            childrenByParent.computeIfAbsent(t.getParentTaskId(), k -> new ArrayList<>()).add(t);
        }
        final Map<Long, SummaryAggregate> result = new HashMap<>();
        for (final Task t : tasks) {
            if (t.getNodeKind() == NodeKind.SUMMARY) {
                result.put(t.getId(), rollupOf(t, childrenByParent, tenantId));
            }
        }
        return result;
    }

    private SummaryAggregate rollupOf(final Task summary, final Map<Long, List<Task>> childrenByParent,
            final long tenantId) {
        final List<Task> leaves = new ArrayList<>();
        collectLeaves(summary.getId(), childrenByParent, leaves);

        Instant start = null;
        Instant finish = null;
        long totalWork = 0L;
        BigDecimal totalCost = null;
        boolean critical = false;
        BigDecimal weightedPct = BigDecimal.ZERO;
        long weightSum = 0L;

        for (final Task leaf : leaves) {
            if (leaf.getStartDate() != null && (start == null || leaf.getStartDate().isBefore(start))) {
                start = leaf.getStartDate();
            }
            if (leaf.getFinishDate() != null && (finish == null || leaf.getFinishDate().isAfter(finish))) {
                finish = leaf.getFinishDate();
            }
            if (Boolean.TRUE.equals(leaf.getCritical())) {
                critical = true;
            }
            long leafWork = 0L;
            for (final Assignment a : assignmentRepository.findAllByTaskIdAndTenantId(leaf.getId(), tenantId)) {
                if (a.getWorkMinutes() != null) {
                    leafWork += a.getWorkMinutes();
                }
                if (a.getCostAmount() != null) {
                    totalCost = totalCost == null ? a.getCostAmount() : totalCost.add(a.getCostAmount());
                }
            }
            totalWork += leafWork;
            final long weight = leafWork > 0 ? leafWork : 1L;
            final BigDecimal pct = leafPercent(leaf, tenantId);
            weightedPct = weightedPct.add(pct.multiply(BigDecimal.valueOf(weight)));
            weightSum += weight;
        }

        final BigDecimal percent = weightSum == 0
                ? BigDecimal.ZERO
                : weightedPct.divide(BigDecimal.valueOf(weightSum), PCT_CONTEXT);
        return new SummaryAggregate(summary.getId(), start, finish, totalWork, totalCost,
                percent, critical, leaves.size());
    }

    private BigDecimal leafPercent(final Task leaf, final long tenantId) {
        final Optional<TaskProgress> progress =
                progressRepository.findByTaskIdAndTenantId(leaf.getId(), tenantId);
        return progress.map(TaskProgress::getPercentComplete).orElse(BigDecimal.ZERO);
    }

    private void collectLeaves(final long parentId, final Map<Long, List<Task>> childrenByParent,
            final List<Task> out) {
        final List<Task> children = childrenByParent.get(parentId);
        if (children == null) {
            return;
        }
        for (final Task child : children) {
            if (child.getNodeKind() == NodeKind.SUMMARY) {
                collectLeaves(child.getId(), childrenByParent, out);
            } else {
                out.add(child);
            }
        }
    }
}

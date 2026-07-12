package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.projection.Altitude;
import fr.pivot.pilotage.schedule.projection.Layout;
import fr.pivot.pilotage.schedule.projection.PlanProjectionService;
import fr.pivot.pilotage.schedule.projection.PlanView;
import fr.pivot.pilotage.schedule.projection.SummaryAggregate;
import fr.pivot.pilotage.schedule.service.SchedulingService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing {@link WbsTaskController} — the WBS (Work Breakdown Structure) of a
 * project's detailed Gantt (F22.4, US22.4.1a/b/c). Owns the tree operations (create, indent,
 * outdent, reorder, read) on the shared temporal graph ({@code fr.pivot.pilotage.schedule.Task}),
 * <strong>never a separate entity</strong> — a WBS node is a plain {@code Task} with a
 * {@code parent_task_id} and a {@code position}.
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> This service does <em>not</em> recompute the
 * WBS numbering nor the summary aggregation itself:
 * <ul>
 *   <li>after every structural change it calls
 *       {@link SchedulingService#scheduleProject(long, long)} (EN22.1b), which re-derives and
 *       persists the {@code wbs_code} of every impacted task via the engine's {@code WbsNumbering};</li>
 *   <li>to expose a summary's aggregated dates/duration/percent it reads the rollups computed by
 *       {@link PlanProjectionService} (EN22.1c) — {@code start=min}, {@code finish=max},
 *       {@code work=Σ}, {@code percent=}charge-weighted mean — never persisted twice on the summary
 *       row.</li>
 * </ul>
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and TODO-SETUP §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId}
 * are explicit arguments, never taken from a request body. Every method resolves the target project
 * first via {@link #requireProject(long, long, long)}, a single tenant+team-scoped lookup collapsing
 * every isolation failure into one non-disclosing {@link WbsProjectNotFoundException} (404).
 */
@Service
public class WbsTaskService {

    /** WBS Gantt tasks are day-grained by default (the detailed schedule altitude). */
    private static final TemporalPrecision DEFAULT_PRECISION = TemporalPrecision.DAY;

    /** Initial revision of a freshly created task. */
    private static final int INITIAL_REVISION = 0;

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskProgressRepository progressRepository;
    private final SchedulingService schedulingService;
    private final PlanProjectionService projectionService;

    /**
     * Constructs the service.
     *
     * @param projectRepository  tenant/team-scoped project repository (EN18.1)
     * @param taskRepository     tenant/team-scoped task (temporal graph) repository (EN22.1a)
     * @param progressRepository task-progress repository (leaf percent complete)
     * @param schedulingService  the EN22.1b engine service that re-derives/persists {@code wbs_code}
     * @param projectionService  the EN22.1c projection that computes summary rollups
     */
    public WbsTaskService(final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final TaskProgressRepository progressRepository, final SchedulingService schedulingService,
            final PlanProjectionService projectionService) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.progressRepository = progressRepository;
        this.schedulingService = schedulingService;
        this.projectionService = projectionService;
    }

    // ---- read (US22.4.1a / US22.4.1c) -----------------------------------------------------------

    /**
     * Reads a project's whole WBS as an ordered, pre-order (depth-first) tree, with server-derived
     * WBS codes, summary aggregates and ARIA attributes for the tree widget.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the ordered WBS tree
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public WbsTreeResponse tree(final long tenantId, final long teamId, final long projectId) {
        requireProject(tenantId, teamId, projectId);
        final List<Task> tasks = taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId);
        final Map<Long, SummaryAggregate> aggregates = aggregates(tenantId, projectId);
        final Map<Long, BigDecimal> leafPercent = leafPercents(tasks, tenantId, teamId);

        final Map<Long, List<Task>> childrenByParent = childrenByParent(tasks);
        final List<WbsTaskResponse> nodes = new ArrayList<>();
        appendPreOrder(null, 1, childrenByParent, aggregates, leafPercent, nodes);
        return WbsTreeResponse.of(projectId, nodes);
    }

    // ---- create (US22.4.1a) ---------------------------------------------------------------------

    /**
     * Creates a task in a project's WBS, optionally under an existing parent, and re-derives the
     * WBS codes of the whole project via the engine. If created under a parent, that parent becomes
     * a {@code SUMMARY} (a node with children is a summary — US22.4.1a).
     *
     * <p><strong>Duration-0 auto-classification (US22.4.6 AC1).</strong> A task created with
     * {@code durationMinutes=0} is a {@code MILESTONE}, not a {@code LEAF} — "given a task of
     * duration 0, when it is created, then it displays as a milestone". A jalon stays modelled as a
     * zero-duration {@code Task} on the shared temporal graph (EN22.1), never a separate entity,
     * consistent with the roadmap-rapide milestone (US22.3.4). Any other duration (including
     * {@code null}, meaning "not yet set") yields a plain {@code LEAF}.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the creation payload (never carries a {@code wbsCode} — derived server-side)
     * @return the created task, WBS-numbered
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if {@code request.parentTaskId()} does not resolve on this
     *                                     project
     */
    @Transactional
    public WbsTaskResponse createTask(final long tenantId, final long teamId, final long projectId,
            final CreateWbsTaskRequest request) {
        requireProject(tenantId, teamId, projectId);

        final Long parentId = request.parentTaskId();
        if (parentId != null) {
            final Task parent = requireTask(tenantId, teamId, projectId, parentId);
            promoteToSummary(parent);
        }

        final int position = request.position() != null
                ? request.position()
                : nextPosition(tenantId, teamId, projectId, parentId);

        final NodeKind kind = request.durationMinutes() != null && request.durationMinutes() == 0
                ? NodeKind.MILESTONE : NodeKind.LEAF;
        final Task task = new Task(tenantId, teamId, projectId, position, request.name(), kind,
                Boolean.FALSE, DEFAULT_PRECISION, INITIAL_REVISION);
        task.setParentTaskId(parentId);
        task.setDurationMinutes(request.durationMinutes());
        final Task saved = taskRepository.save(task);

        schedulingService.scheduleProject(projectId, tenantId);
        return read(tenantId, teamId, projectId, saved.getId());
    }

    // ---- move: indent / outdent / reorder (US22.4.1b) -------------------------------------------

    /**
     * Indents a task: it becomes a sub-task of its immediately preceding sibling (US22.4.1b).
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to indent
     * @return the indented task, re-numbered
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if the task does not resolve on this project
     * @throws IllegalWbsMoveException     if the task is the first among its siblings (no possible
     *                                     parent)
     */
    @Transactional
    public WbsTaskResponse indent(final long tenantId, final long teamId, final long projectId, final long taskId) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);
        final Task precedingSibling = precedingSibling(tenantId, teamId, projectId, task);
        if (precedingSibling == null) {
            throw IllegalWbsMoveException.indentFirstTask(taskId);
        }
        promoteToSummary(precedingSibling);
        task.setParentTaskId(precedingSibling.getId());
        task.setPosition(nextPosition(tenantId, teamId, projectId, precedingSibling.getId()));
        return applyStructuralChange(tenantId, teamId, projectId, task);
    }

    /**
     * Outdents a task: it rises one level, becoming a sibling of its former parent, placed just
     * after it (US22.4.1b).
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to outdent
     * @return the outdented task, re-numbered
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if the task does not resolve on this project
     * @throws IllegalWbsMoveException     if the task is already at the WBS root
     */
    @Transactional
    public WbsTaskResponse outdent(final long tenantId, final long teamId, final long projectId, final long taskId) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);
        final Long parentId = task.getParentTaskId();
        if (parentId == null) {
            throw IllegalWbsMoveException.outdentRoot(taskId);
        }
        final Task parent = requireTask(tenantId, teamId, projectId, parentId);
        task.setParentTaskId(parent.getParentTaskId());
        task.setPosition((parent.getPosition() == null ? 0 : parent.getPosition()) + 1);
        final WbsTaskResponse result = applyStructuralChange(tenantId, teamId, projectId, task);
        demoteIfChildless(tenantId, teamId, projectId, parent);
        return result;
    }

    /**
     * Reorders a task among its current siblings (moves it up or down) — the parent is unchanged,
     * only the display {@code position} changes; the WBS numbering is re-derived (US22.4.1b).
     *
     * @param tenantId    the requesting tenant's {@code public.tenants.id}
     * @param teamId      the requesting team's {@code public.teams.id}
     * @param projectId   the project id
     * @param taskId      the task to reorder
     * @param newPosition the requested new position among its siblings
     * @return the reordered task, re-numbered
     * @throws WbsProjectNotFoundException if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException    if the task does not resolve on this project
     */
    @Transactional
    public WbsTaskResponse reorder(final long tenantId, final long teamId, final long projectId,
            final long taskId, final int newPosition) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);
        task.setPosition(newPosition);
        return applyStructuralChange(tenantId, teamId, projectId, task);
    }

    /**
     * Combined structural move (US22.4.1b) — reparent and/or reposition in one call, with the same
     * validations as the dedicated verbs. A reparent to {@link MoveWbsTaskRequest#ROOT} lifts the
     * task to the WBS root; reparenting a task under one of its own descendants is rejected as a
     * hierarchy cycle (409, decision D4).
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to move
     * @param request   the move payload
     * @return the moved task, re-numbered
     * @throws WbsProjectNotFoundException  if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException     if the task or a supplied parent does not resolve
     * @throws WbsHierarchyCycleException   if the reparent would make the task its own ancestor
     */
    @Transactional
    public WbsTaskResponse move(final long tenantId, final long teamId, final long projectId,
            final long taskId, final MoveWbsTaskRequest request) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);
        final Long formerParentId = task.getParentTaskId();

        if (request.reparents()) {
            final Long newParentId = request.toRoot() ? null : request.parentTaskId();
            if (newParentId != null) {
                final Task newParent = requireTask(tenantId, teamId, projectId, newParentId);
                requireNoCycle(tenantId, teamId, projectId, taskId, newParentId);
                promoteToSummary(newParent);
            }
            task.setParentTaskId(newParentId);
        }
        if (request.position() != null) {
            task.setPosition(request.position());
        }
        final WbsTaskResponse result = applyStructuralChange(tenantId, teamId, projectId, task);
        if (request.reparents() && formerParentId != null && !formerParentId.equals(task.getParentTaskId())) {
            requireTask(tenantId, teamId, projectId, formerParentId);
            demoteIfChildless(tenantId, teamId, projectId,
                    requireTask(tenantId, teamId, projectId, formerParentId));
        }
        return result;
    }

    // ---- edit (US22.4.1c error AC) --------------------------------------------------------------

    /**
     * Rejects a direct edit of a summary task's derived field (US22.4.1c error AC) — the guard the
     * controller calls before any leaf edit path. A summary's dates/duration/percent are aggregated
     * from its sub-tasks and are read-only; editing them is refused with {@code 422}.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task whose derived field an edit targets
     * @param field     the derived field name (for the error message)
     * @throws WbsProjectNotFoundException      if the project is not visible to the tenant/team
     * @throws WbsTaskNotFoundException          if the task does not resolve on this project
     * @throws DerivedFieldNotEditableException if the task is a summary (its field is derived)
     */
    @Transactional(readOnly = true)
    public void assertDerivedFieldEditable(final long tenantId, final long teamId, final long projectId,
            final long taskId, final String field) {
        requireProject(tenantId, teamId, projectId);
        final Task task = requireTask(tenantId, teamId, projectId, taskId);
        if (task.getNodeKind() == NodeKind.SUMMARY) {
            throw DerivedFieldNotEditableException.summaryField(taskId, field);
        }
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * Persists a structural change, normalises the moved task's sibling group to contiguous
     * {@code 0..n-1} positions (so an absolute target position actually reorders the siblings rather
     * than merely colliding with them), re-derives the whole project's WBS codes via the engine, and
     * returns the refreshed view of the moved task.
     */
    private WbsTaskResponse applyStructuralChange(final long tenantId, final long teamId, final long projectId,
            final Task task) {
        task.setRevision((task.getRevision() == null ? 0 : task.getRevision()) + 1);
        taskRepository.save(task);
        resequenceSiblings(tenantId, teamId, projectId, task.getParentTaskId(), task.getId());
        schedulingService.scheduleProject(projectId, tenantId);
        return read(tenantId, teamId, projectId, task.getId());
    }

    /**
     * Rewrites the positions of every task sharing {@code parentId} to a contiguous {@code 0..n-1}
     * sequence, honouring {@code movedTaskId}'s requested position: siblings are ordered by
     * {@code (position, id)} (the moved task's stored position is its requested landing index), then
     * the moved task is placed at that index and everyone is re-stamped {@code 0, 1, 2, …}. This is
     * the MS-Project reorder semantics — moving a task down shifts the intervening siblings up.
     */
    private void resequenceSiblings(final long tenantId, final long teamId, final long projectId,
            final Long parentId, final long movedTaskId) {
        final List<Task> siblings = new ArrayList<>();
        for (final Task t : taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)) {
            if (java.util.Objects.equals(t.getParentTaskId(), parentId)) {
                siblings.add(t);
            }
        }
        // Order by requested position; the moved task wins ties so it lands *before* an incumbent
        // sharing its target index (i.e. it is inserted at that index, shifting the rest down).
        siblings.sort(Comparator
                .comparingInt((Task t) -> t.getPosition() == null ? 0 : t.getPosition())
                .thenComparingInt((Task t) -> t.getId() == movedTaskId ? 0 : 1)
                .thenComparing(Task::getId));
        int pos = 0;
        final List<Task> dirty = new ArrayList<>();
        for (final Task t : siblings) {
            if (t.getPosition() == null || t.getPosition() != pos) {
                t.setPosition(pos);
                dirty.add(t);
            }
            pos++;
        }
        if (!dirty.isEmpty()) {
            taskRepository.saveAll(dirty);
        }
    }

    /**
     * Reads a single WBS node (after a mutation) with its derived code, aggregates and ARIA
     * attributes — computed by walking the freshly persisted tree so the returned position/level
     * reflect the post-move state.
     */
    private WbsTaskResponse read(final long tenantId, final long teamId, final long projectId, final long taskId) {
        return tree(tenantId, teamId, projectId).nodes().stream()
                .filter(n -> n.taskId() == taskId)
                .findFirst()
                .orElseThrow(() -> new WbsTaskNotFoundException(taskId, projectId));
    }

    /**
     * Detects a WBS-hierarchy cycle: reparenting {@code taskId} under {@code newParentId} is a cycle
     * if {@code taskId} is {@code newParentId} itself or an ancestor of it (i.e. {@code newParentId}
     * is a descendant of {@code taskId}). Walks {@code newParentId}'s ancestor chain up to the root.
     */
    private void requireNoCycle(final long tenantId, final long teamId, final long projectId,
            final long taskId, final long newParentId) {
        if (taskId == newParentId) {
            throw new WbsHierarchyCycleException(taskId, newParentId);
        }
        final Map<Long, Long> parentOf = new HashMap<>();
        for (final Task t : taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)) {
            parentOf.put(t.getId(), t.getParentTaskId());
        }
        Long ancestor = parentOf.get(newParentId);
        while (ancestor != null) {
            if (ancestor == taskId) {
                throw new WbsHierarchyCycleException(taskId, newParentId);
            }
            ancestor = parentOf.get(ancestor);
        }
    }

    /**
     * Returns the sibling immediately preceding {@code task} (same parent, greatest position strictly
     * below {@code task}'s, tie-broken by id), or {@code null} if {@code task} is the first sibling.
     */
    private Task precedingSibling(final long tenantId, final long teamId, final long projectId, final Task task) {
        final List<Task> siblings = new ArrayList<>();
        for (final Task t : taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)) {
            if (java.util.Objects.equals(t.getParentTaskId(), task.getParentTaskId()) && !t.getId().equals(task.getId())) {
                siblings.add(t);
            }
        }
        siblings.sort(orderComparator());
        Task preceding = null;
        for (final Task s : siblings) {
            if (comesBefore(s, task)) {
                preceding = s;
            } else {
                break;
            }
        }
        return preceding;
    }

    private static boolean comesBefore(final Task candidate, final Task pivot) {
        final int cp = candidate.getPosition() == null ? 0 : candidate.getPosition();
        final int pp = pivot.getPosition() == null ? 0 : pivot.getPosition();
        if (cp != pp) {
            return cp < pp;
        }
        return candidate.getId() < pivot.getId();
    }

    private void promoteToSummary(final Task task) {
        if (task.getNodeKind() == NodeKind.LEAF) {
            task.setNodeKind(NodeKind.SUMMARY);
            taskRepository.save(task);
        }
    }

    /**
     * Reverts a former parent back to {@code LEAF} once it has no remaining children — keeps the
     * SUMMARY/LEAF classification consistent after an outdent/move away from it.
     */
    private void demoteIfChildless(final long tenantId, final long teamId, final long projectId, final Task parent) {
        final boolean hasChildren = taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .stream().anyMatch(t -> parent.getId().equals(t.getParentTaskId()));
        if (!hasChildren && parent.getNodeKind() == NodeKind.SUMMARY) {
            parent.setNodeKind(NodeKind.LEAF);
            taskRepository.save(parent);
            schedulingService.scheduleProject(projectId, tenantId);
        }
    }

    private int nextPosition(final long tenantId, final long teamId, final long projectId, final Long parentId) {
        int max = -1;
        for (final Task t : taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId)) {
            if (java.util.Objects.equals(t.getParentTaskId(), parentId)) {
                final int pos = t.getPosition() == null ? 0 : t.getPosition();
                if (pos > max) {
                    max = pos;
                }
            }
        }
        return max + 1;
    }

    private Map<Long, SummaryAggregate> aggregates(final long tenantId, final long projectId) {
        return projectionService.project(projectId, tenantId, Altitude.DETAIL, Layout.GANTT)
                .map(PlanView::aggregates)
                .orElse(Map.of());
    }

    private Map<Long, BigDecimal> leafPercents(final List<Task> tasks, final long tenantId, final long teamId) {
        final Map<Long, BigDecimal> percents = new HashMap<>();
        for (final Task t : tasks) {
            progressRepository.findByTaskIdAndTenantIdAndTeamId(t.getId(), tenantId, teamId)
                    .map(TaskProgress::getPercentComplete)
                    .ifPresent(p -> percents.put(t.getId(), p));
        }
        return percents;
    }

    private static Map<Long, List<Task>> childrenByParent(final List<Task> tasks) {
        final Map<Long, List<Task>> byParent = new HashMap<>();
        for (final Task t : tasks) {
            byParent.computeIfAbsent(t.getParentTaskId(), k -> new ArrayList<>()).add(t);
        }
        for (final List<Task> siblings : byParent.values()) {
            siblings.sort(orderComparator());
        }
        return byParent;
    }

    private static Comparator<Task> orderComparator() {
        return Comparator.comparingInt((Task t) -> t.getPosition() == null ? 0 : t.getPosition())
                .thenComparing(Task::getId);
    }

    /**
     * Appends every child of {@code parentId} (and, recursively, their subtrees) to {@code out} in
     * pre-order, computing each node's ARIA level/set-size/pos-in-set and folding a summary's
     * aggregated fields from {@code aggregates}.
     */
    private void appendPreOrder(final Long parentId, final int level, final Map<Long, List<Task>> childrenByParent,
            final Map<Long, SummaryAggregate> aggregates, final Map<Long, BigDecimal> leafPercent,
            final List<WbsTaskResponse> out) {
        final List<Task> siblings = childrenByParent.get(parentId);
        if (siblings == null) {
            return;
        }
        final int setSize = siblings.size();
        int posInSet = 1;
        for (final Task t : siblings) {
            out.add(toResponse(t, level, setSize, posInSet, aggregates, leafPercent));
            appendPreOrder(t.getId(), level + 1, childrenByParent, aggregates, leafPercent, out);
            posInSet++;
        }
    }

    private static WbsTaskResponse toResponse(final Task t, final int level, final int setSize, final int posInSet,
            final Map<Long, SummaryAggregate> aggregates, final Map<Long, BigDecimal> leafPercent) {
        final boolean summary = t.getNodeKind() == NodeKind.SUMMARY;
        final SummaryAggregate agg = summary ? aggregates.get(t.getId()) : null;

        final var start = agg != null ? agg.rollupStart() : t.getStartDate();
        final var finish = agg != null ? agg.rollupFinish() : t.getFinishDate();
        final Integer duration = agg != null ? Integer.valueOf((int) agg.totalWorkMinutes()) : t.getDurationMinutes();
        final BigDecimal percent = agg != null
                ? agg.percentComplete()
                : leafPercent.getOrDefault(t.getId(), null);
        final String progressLabel = percent != null ? percent.stripTrailingZeros().toPlainString() + "%" : null;

        return new WbsTaskResponse(t.getId(), t.getParentTaskId(), t.getWbsCode(), t.getName(),
                t.getNodeKind(), t.getPosition() == null ? 0 : t.getPosition(), start, finish, duration,
                percent, progressLabel, summary, WbsTaskResponse.ARIA_ROLE_TREEITEM, level, setSize,
                posInSet, summary, WbsTaskResponse.labelFor(t.getNodeKind()),
                t.getRevision() == null ? 0 : t.getRevision());
    }

    // ---- shared guards --------------------------------------------------------------------------

    /**
     * Resolves the target project within the tenant/team boundary — the single isolation check
     * shared by every WBS operation.
     */
    private Project requireProject(final long tenantId, final long teamId, final long projectId) {
        return projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsProjectNotFoundException(projectId, tenantId, teamId));
    }

    /**
     * Resolves a task within the project/tenant/team boundary.
     */
    private Task requireTask(final long tenantId, final long teamId, final long projectId, final long taskId) {
        return taskRepository.findByIdAndProjectIdAndTenantIdAndTeamId(taskId, projectId, tenantId, teamId)
                .orElseThrow(() -> new WbsTaskNotFoundException(taskId, projectId));
    }
}

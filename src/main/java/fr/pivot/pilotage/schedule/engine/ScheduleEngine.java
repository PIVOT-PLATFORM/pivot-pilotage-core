package fr.pivot.pilotage.schedule.engine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, in-memory CPM scheduling engine (EN22.1b, frozen contract §b).
 *
 * <p><strong>Purity.</strong> The engine is a pure function of its {@link ScheduleInput}: no
 * {@code Instant.now()}, no randomness, no I/O, no FK, no inter-module read, no Spring. Two runs on
 * equal inputs produce value-identical {@link ScheduleResult}s (determinism D1). Tie-breaks use the
 * total order {@code (wbsPath, taskId)} (D2).
 *
 * <p><strong>Granularity: the worked hour.</strong> Durations and lags are snapped up to a whole
 * worked hour ({@link WorkingCalendar#snapToHour(long)}) before projection onto the calendar's
 * worked-minute axis; float is expressed in worked minutes (a multiple of 60).
 *
 * <p><strong>Algorithm.</strong>
 * <ol>
 *   <li><em>Forward pass</em> (topological, upstream→downstream): {@code ES(succ)} is the max over
 *       all incoming edges of the predecessor bound projected through the calendar plus lag, floored
 *       by SNET/FNET/MSO/MFO constraints; {@code EF = advance(ES, duration)}.</li>
 *   <li><em>Backward pass</em> (downstream→upstream): {@code LF} starts from the project finish or
 *       each task's ALAP/SNLT/FNLT/MFO ceiling and is pulled earlier by successors; {@code LS =
 *       retreat(LF, duration)}.</li>
 *   <li><em>Float</em>: {@code totalFloat = workedMinutes(ES..LS)}; {@code freeFloat = min over
 *       successors of (ES(succ) - EF - lag)} bounded at 0. Critical ⇔ {@code totalFloat <= epsilon}
 *       (epsilon = 0).</li>
 *   <li><em>Summaries</em>: start=min(child ES), finish=max(child EF), critical if any leaf child is
 *       critical — computed, never stored twice.</li>
 * </ol>
 *
 * <p><strong>AUTO vs MANUAL.</strong> AUTO tasks are recomputed. MANUAL tasks keep their pinned
 * dates; the engine still computes the theoretical AUTO date and emits a {@link ManualVariance}. A
 * hard dependency is never broken: a constraint that would fight it is dropped and a
 * {@link SchedulingWarning} of type {@code CONSTRAINT_CONFLICT} is emitted.
 *
 * <p><strong>Rejections.</strong> A dependency cycle raises {@link ScheduleErrorCode#SCHEDULE_CYCLE}
 * with no partial state; a multi-tenant input raises {@link ScheduleErrorCode#TENANT_VIOLATION}; an
 * unresolvable calendar raises {@link ScheduleErrorCode#UNKNOWN_CALENDAR}.
 */
public final class ScheduleEngine {

    /** Critical-path epsilon (worked minutes): a task is critical when totalFloat &le; this. */
    private static final long EPSILON_MINUTES = 0L;

    /** Runs a full CPM schedule (fresh version 0). */
    public ScheduleResult schedule(final ScheduleInput input) {
        return schedule(input, 0L);
    }

    /**
     * Runs a full CPM schedule producing a result stamped with the given version.
     *
     * @param input   the mono-tenant project snapshot
     * @param version the schedule version to stamp
     * @return the CPM result
     * @throws ScheduleException on cycle, tenant violation or unknown calendar
     */
    public ScheduleResult schedule(final ScheduleInput input, final long version) {
        verifyTenant(input);

        final Map<Long, TaskNode> byId = indexTasks(input);
        final List<TaskNode> leaves = leavesInOrder(input, byId);
        final Map<Long, List<DependencyEdge>> incoming = incomingEdges(input);
        final Map<Long, List<DependencyEdge>> outgoing = outgoingEdges(input);
        final List<Long> topo = topologicalOrder(leaves, incoming, byId);

        final Map<Long, Instant> es = new HashMap<>();
        final Map<Long, Instant> ef = new HashMap<>();
        final Map<Long, Instant> ls = new HashMap<>();
        final Map<Long, Instant> lf = new HashMap<>();
        final List<SchedulingWarning> warnings = new ArrayList<>();
        final List<ManualVariance> variances = new ArrayList<>();

        forwardPass(input, byId, incoming, topo, es, ef, warnings, variances);
        final Instant projectFinish = projectFinish(topo, ef, input.projectStart());
        backwardPass(input, byId, outgoing, topo, es, ef, ls, lf, projectFinish, warnings);

        final Map<Long, TaskSchedule> results = new LinkedHashMap<>();
        buildLeafResults(input, byId, leaves, outgoing, es, ef, ls, lf, results, warnings);
        aggregateSummaries(input, byId, results);

        final List<Long> criticalPath = criticalPath(input, byId, results);
        final Instant computedAt = input.dataDate() != null ? input.dataDate() : input.projectStart();
        final long hash = InputHash.of(input);
        return new ScheduleResult(results, criticalPath, warnings, variances, computedAt, version, hash);
    }

    /**
     * Incremental recompute (EN22.1b, frozen contract §b): applies the delta to the previous state
     * and returns a DIFF (per-task patch + new critical path only if it changed).
     *
     * <p><strong>Convergence (D3, anti-drift oracle).</strong> The new absolute schedule is computed
     * by a full {@code schedule} of {@code prev.apply(delta)} at version {@code baseVersion+1}, so
     * {@code reSchedule(state, delta)} is byte-identical to {@code schedule(apply(input, delta))}.
     * The returned diff is intrinsically bounded: only tasks whose CPM values actually changed
     * appear (the downstream transitive closure + ancestor summaries), because unaffected tasks
     * produce a {@code null} {@link TaskPatch#diff}.
     *
     * <p><strong>Idempotence.</strong> An empty change set yields an empty patch and the same
     * critical path (reported as unchanged), keeping the version stable.
     *
     * <p><strong>Atomicity.</strong> A delta that introduces a cycle is rejected as a whole:
     * {@link ScheduleErrorCode#SCHEDULE_CYCLE} is caught, no partial state is produced, and an empty
     * DIFF carrying a {@code REJECTED} warning is returned (the ops are not applied).
     *
     * @param prev  the previous co-editing state
     * @param delta the atomic change set (its {@code baseVersion} must match {@code prev.version()})
     * @return the DIFF
     * @throws ScheduleException {@code STALE_BASE_VERSION} if the delta targets a stale version
     */
    public ScheduleDiff reSchedule(final ScheduleState prev, final ChangeSet delta) {
        if (delta.baseVersion() != prev.version()) {
            throw new ScheduleException(ScheduleErrorCode.STALE_BASE_VERSION,
                    "delta baseVersion " + delta.baseVersion() + " != current " + prev.version()
                            + "; rebase required");
        }
        final long producedVersion = prev.version() + 1;
        if (delta.isEmpty()) {
            // Idempotence D3: empty delta ⇒ empty patch, critical path reported unchanged.
            return new ScheduleDiff(List.of(), null, List.of(), List.of(),
                    prev.result().variances(), prev.version(), prev.version());
        }

        final ScheduleInput newInput = prev.apply(delta);
        final ScheduleResult after;
        try {
            after = schedule(newInput, producedVersion);
        } catch (final ScheduleException ex) {
            if (ex.code() == ScheduleErrorCode.SCHEDULE_CYCLE) {
                // Atomic rejection: no op applied, empty patch, typed warning.
                return new ScheduleDiff(List.of(), null, List.of(),
                        List.of(new SchedulingWarning(SchedulingWarning.WarningType.REJECTED,
                                0L, "delta rejected: it introduces a dependency cycle")),
                        prev.result().variances(), prev.version(), prev.version());
            }
            throw ex;
        }

        final ScheduleResult before = prev.result();
        final List<TaskPatch> patches = new ArrayList<>();
        for (final Map.Entry<Long, TaskSchedule> entry : after.tasks().entrySet()) {
            final TaskPatch p = TaskPatch.diff(before.task(entry.getKey()), entry.getValue());
            if (p != null) {
                patches.add(p);
            }
        }
        patches.sort(Comparator.comparingLong(TaskPatch::taskId));

        final List<Long> removed = new ArrayList<>();
        for (final Long id : before.tasks().keySet()) {
            if (!after.tasks().containsKey(id)) {
                removed.add(id);
            }
        }
        removed.sort(Comparator.naturalOrder());

        final boolean cpChanged = !before.criticalPathSorted().equals(after.criticalPathSorted());
        final List<Long> newCp = cpChanged ? after.criticalPath() : null;

        return new ScheduleDiff(patches, newCp, removed, after.warnings(), after.variances(),
                prev.version(), producedVersion);
    }

    // ------------------------------------------------------------------ tenant + indexing -----

    private void verifyTenant(final ScheduleInput input) {
        for (final TaskNode t : input.tasks()) {
            // Tasks carry no tenant field individually; the guard is that the input is one tenant.
            // A cross-tenant mix is caught at the service boundary; here we assert edges stay in-graph.
            if (t.constraintKind() != null && t.constraintKind() != ConstraintKind.ASAP
                    && t.constraintKind() != ConstraintKind.ALAP && t.constraintDate() == null) {
                throw new IllegalArgumentException(
                        "constraint " + t.constraintKind() + " on task " + t.id() + " requires a date");
            }
        }
        final Map<Long, TaskNode> byId = new HashMap<>();
        for (final TaskNode t : input.tasks()) {
            byId.put(t.id(), t);
        }
        for (final DependencyEdge e : input.dependencies()) {
            if (!byId.containsKey(e.predecessorId()) || !byId.containsKey(e.successorId())) {
                throw new ScheduleException(ScheduleErrorCode.TENANT_VIOLATION,
                        "dependency " + e.predecessorId() + "->" + e.successorId()
                                + " references a task outside this project snapshot");
            }
        }
    }

    private Map<Long, TaskNode> indexTasks(final ScheduleInput input) {
        final Map<Long, TaskNode> byId = new LinkedHashMap<>();
        for (final TaskNode t : input.tasksInCanonicalOrder()) {
            byId.put(t.id(), t);
        }
        return byId;
    }

    private List<TaskNode> leavesInOrder(final ScheduleInput input, final Map<Long, TaskNode> byId) {
        final List<TaskNode> leaves = new ArrayList<>();
        for (final TaskNode t : input.tasksInCanonicalOrder()) {
            if (t.nodeType() != NodeType.SUMMARY) {
                leaves.add(t);
            }
        }
        return leaves;
    }

    private Map<Long, List<DependencyEdge>> incomingEdges(final ScheduleInput input) {
        final Map<Long, List<DependencyEdge>> map = new HashMap<>();
        for (final DependencyEdge e : input.dependenciesInCanonicalOrder()) {
            map.computeIfAbsent(e.successorId(), k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    private Map<Long, List<DependencyEdge>> outgoingEdges(final ScheduleInput input) {
        final Map<Long, List<DependencyEdge>> map = new HashMap<>();
        for (final DependencyEdge e : input.dependenciesInCanonicalOrder()) {
            map.computeIfAbsent(e.predecessorId(), k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    // ------------------------------------------------------------------ topological order ------

    /**
     * Kahn's algorithm over leaf/milestone/recurring nodes with a deterministic tie-break: ready
     * nodes are popped in {@code (wbsPath, taskId)} order. A remaining node ⇒ a cycle.
     */
    private List<Long> topologicalOrder(final List<TaskNode> leaves,
            final Map<Long, List<DependencyEdge>> incoming, final Map<Long, TaskNode> byId) {
        final Map<Long, Integer> indegree = new HashMap<>();
        for (final TaskNode t : leaves) {
            final List<DependencyEdge> in = incoming.getOrDefault(t.id(), List.of());
            int deg = 0;
            for (final DependencyEdge e : in) {
                if (byId.containsKey(e.predecessorId()) && byId.get(e.predecessorId()).nodeType() != NodeType.SUMMARY) {
                    deg++;
                }
            }
            indegree.put(t.id(), deg);
        }
        final Comparator<Long> tieBreak = Comparator.comparing(id -> byId.get(id).tieBreakKey());
        final List<Long> ready = new ArrayList<>();
        for (final TaskNode t : leaves) {
            if (indegree.get(t.id()) == 0) {
                ready.add(t.id());
            }
        }
        ready.sort(tieBreak);
        final Deque<Long> queue = new ArrayDeque<>(ready);
        final List<Long> order = new ArrayList<>();
        // Successor edges indexed by predecessor for the peeling step.
        final Map<Long, List<Long>> succ = new HashMap<>();
        for (final Long id : indegree.keySet()) {
            succ.put(id, new ArrayList<>());
        }
        for (final Long id : indegree.keySet()) {
            for (final DependencyEdge e : incoming.getOrDefault(id, List.of())) {
                if (succ.containsKey(e.predecessorId())) {
                    succ.get(e.predecessorId()).add(id);
                }
            }
        }
        while (!queue.isEmpty()) {
            final Long id = queue.poll();
            order.add(id);
            final List<Long> outs = new ArrayList<>(succ.getOrDefault(id, List.of()));
            outs.sort(tieBreak);
            final List<Long> newlyReady = new ArrayList<>();
            for (final Long s : outs) {
                final int d = indegree.get(s) - 1;
                indegree.put(s, d);
                if (d == 0) {
                    newlyReady.add(s);
                }
            }
            newlyReady.sort(tieBreak);
            for (final Long s : newlyReady) {
                queue.add(s);
            }
        }
        if (order.size() != leaves.size()) {
            throw new ScheduleException(ScheduleErrorCode.SCHEDULE_CYCLE,
                    "dependency cycle detected: " + (leaves.size() - order.size()) + " task(s) unresolved");
        }
        return order;
    }

    // ------------------------------------------------------------------ forward pass -----------

    private void forwardPass(final ScheduleInput input, final Map<Long, TaskNode> byId,
            final Map<Long, List<DependencyEdge>> incoming, final List<Long> topo,
            final Map<Long, Instant> es, final Map<Long, Instant> ef,
            final List<SchedulingWarning> warnings, final List<ManualVariance> variances) {
        for (final Long id : topo) {
            final TaskNode t = byId.get(id);
            final WorkingCalendar cal = input.calendarFor(t);
            final long duration = WorkingCalendar.snapToHour(t.durationMinutes());

            // 1. dependency-driven earliest start (hard — never violated)
            Instant start = cal.snapForward(input.projectStart());
            for (final DependencyEdge e : incoming.getOrDefault(id, List.of())) {
                final Instant depDriven = earliestFromEdge(e, cal, es, ef, duration);
                if (depDriven != null && depDriven.isAfter(start)) {
                    start = depDriven;
                }
            }
            final Instant depFloor = start;

            // 2. forward constraints (SNET/FNET/MSO/MFO) push start no earlier
            Instant constrained = applyForwardConstraint(t, cal, start, duration);
            if (constrained.isBefore(depFloor)) {
                // constraint would move the task before its hard predecessor → honour dependency
                warnings.add(new SchedulingWarning(SchedulingWarning.WarningType.CONSTRAINT_CONFLICT,
                        id, "constraint " + t.constraintKind() + " precedes a hard dependency; dependency honoured"));
                constrained = depFloor;
            }
            start = constrained;

            final Instant autoStart = cal.snapForward(start);
            final Instant autoFinish = cal.advance(autoStart, duration);

            if (t.mode() == TaskMode.MANUAL && t.manualStart() != null) {
                final Instant mStart = cal.snapForward(t.manualStart());
                final Instant mFinish = t.manualFinish() != null
                        ? cal.snapForward(t.manualFinish()) : cal.advance(mStart, duration);
                es.put(id, mStart);
                ef.put(id, mFinish);
                final long delta = signedWorkedMinutes(cal, autoStart, mStart);
                variances.add(new ManualVariance(id, mStart, autoStart, delta));
            } else {
                es.put(id, autoStart);
                ef.put(id, autoFinish);
            }

            if (t.deadline() != null && ef.get(id).isAfter(t.deadline())) {
                warnings.add(new SchedulingWarning(SchedulingWarning.WarningType.DEADLINE_MISSED,
                        id, "early finish " + ef.get(id) + " is past deadline " + t.deadline()));
            }
        }
    }

    /** Earliest start implied by one incoming edge, given predecessor ES/EF and this task duration. */
    private Instant earliestFromEdge(final DependencyEdge e, final WorkingCalendar cal,
            final Map<Long, Instant> es, final Map<Long, Instant> ef, final long duration) {
        final long lag = WorkingCalendar.snapToHour(Math.abs(e.lagMinutes()))
                * (e.lagMinutes() < 0 ? -1 : 1);
        final Instant predEs = es.get(e.predecessorId());
        final Instant predEf = ef.get(e.predecessorId());
        if (predEs == null || predEf == null) {
            return null;
        }
        return switch (e.linkType()) {
            case FS -> shift(cal, predEf, lag);
            case SS -> shift(cal, predEs, lag);
            // FF: successor finish >= pred finish + lag ⇒ successor start >= that - duration
            case FF -> cal.retreat(shift(cal, predEf, lag), duration);
            // SF: successor finish >= pred start + lag ⇒ successor start >= that - duration
            case SF -> cal.retreat(shift(cal, predEs, lag), duration);
        };
    }

    private Instant shift(final WorkingCalendar cal, final Instant base, final long lag) {
        if (lag == 0) {
            return cal.snapForward(base);
        }
        return lag > 0 ? cal.advance(base, lag) : cal.retreat(base, -lag);
    }

    private Instant applyForwardConstraint(final TaskNode t, final WorkingCalendar cal,
            final Instant start, final long duration) {
        if (t.constraintKind() == null) {
            return start;
        }
        return switch (t.constraintKind()) {
            case SNET, MSO -> laterOf(start, cal.snapForward(t.constraintDate()));
            case FNET, MFO -> laterOf(start, cal.retreat(cal.snapForward(t.constraintDate()), duration));
            default -> start; // ASAP/ALAP/SNLT/FNLT do not push the forward floor
        };
    }

    private Instant laterOf(final Instant a, final Instant b) {
        return a.isAfter(b) ? a : b;
    }

    // ------------------------------------------------------------------ backward pass ----------

    private Instant projectFinish(final List<Long> topo, final Map<Long, Instant> ef,
            final Instant fallback) {
        Instant max = fallback;
        for (final Long id : topo) {
            final Instant f = ef.get(id);
            if (f != null && f.isAfter(max)) {
                max = f;
            }
        }
        return max;
    }

    private void backwardPass(final ScheduleInput input, final Map<Long, TaskNode> byId,
            final Map<Long, List<DependencyEdge>> outgoing, final List<Long> topo,
            final Map<Long, Instant> es, final Map<Long, Instant> ef,
            final Map<Long, Instant> ls, final Map<Long, Instant> lf,
            final Instant projectFinish, final List<SchedulingWarning> warnings) {
        for (int i = topo.size() - 1; i >= 0; i--) {
            final Long id = topo.get(i);
            final TaskNode t = byId.get(id);
            final WorkingCalendar cal = input.calendarFor(t);
            final long duration = WorkingCalendar.snapToHour(t.durationMinutes());

            Instant finish = projectFinish;
            boolean hasSucc = false;
            for (final DependencyEdge e : outgoing.getOrDefault(id, List.of())) {
                final Instant latest = latestFromEdge(e, cal, ls, lf, duration);
                if (latest != null) {
                    finish = hasSucc ? earlierOf(finish, latest) : latest;
                    hasSucc = true;
                }
            }
            // Backward constraints (ALAP/SNLT/FNLT/MFO) pull the late finish earlier.
            finish = applyBackwardConstraint(t, cal, finish, duration);
            if (finish.isBefore(ef.get(id))) {
                // over-constrained: late finish earlier than early finish → negative float
                warnings.add(new SchedulingWarning(SchedulingWarning.WarningType.NEGATIVE_FLOAT,
                        id, "late finish " + finish + " precedes early finish " + ef.get(id)));
            }
            final Instant snappedFinish = cal.snapBackward(finish);
            lf.put(id, snappedFinish);
            ls.put(id, cal.retreat(snappedFinish, duration));
        }
    }

    private Instant latestFromEdge(final DependencyEdge e, final WorkingCalendar cal,
            final Map<Long, Instant> ls, final Map<Long, Instant> lf, final long predDuration) {
        final long lag = WorkingCalendar.snapToHour(Math.abs(e.lagMinutes()))
                * (e.lagMinutes() < 0 ? -1 : 1);
        final Instant succLs = ls.get(e.successorId());
        final Instant succLf = lf.get(e.successorId());
        if (succLs == null || succLf == null) {
            return null;
        }
        return switch (e.linkType()) {
            // FS: succ LS >= pred LF + lag ⇒ pred LF <= succ LS - lag
            case FS -> shiftBack(cal, succLs, lag);
            // SS: succ LS >= pred LS + lag ⇒ pred LF <= (succ LS - lag) + predDuration
            case SS -> cal.advance(shiftBack(cal, succLs, lag), predDuration);
            // FF: succ LF >= pred LF + lag ⇒ pred LF <= succ LF - lag
            case FF -> shiftBack(cal, succLf, lag);
            // SF: succ LF >= pred LS + lag ⇒ pred LF <= (succ LF - lag) + predDuration
            case SF -> cal.advance(shiftBack(cal, succLf, lag), predDuration);
        };
    }

    private Instant shiftBack(final WorkingCalendar cal, final Instant base, final long lag) {
        if (lag == 0) {
            return cal.snapBackward(base);
        }
        return lag > 0 ? cal.retreat(base, lag) : cal.advance(base, -lag);
    }

    private Instant applyBackwardConstraint(final TaskNode t, final WorkingCalendar cal,
            final Instant finish, final long duration) {
        if (t.constraintKind() == null) {
            return finish;
        }
        return switch (t.constraintKind()) {
            case SNLT -> earlierOf(finish, cal.advance(cal.snapForward(t.constraintDate()), duration));
            case FNLT, MFO -> earlierOf(finish, cal.snapBackward(t.constraintDate()));
            case MSO -> earlierOf(finish, cal.advance(cal.snapForward(t.constraintDate()), duration));
            default -> finish; // ASAP/SNET/FNET keep the project finish ceiling
        };
    }

    private Instant earlierOf(final Instant a, final Instant b) {
        return a.isBefore(b) ? a : b;
    }

    // ------------------------------------------------------------------ leaf results + float ---

    private void buildLeafResults(final ScheduleInput input, final Map<Long, TaskNode> byId,
            final List<TaskNode> leaves, final Map<Long, List<DependencyEdge>> outgoing,
            final Map<Long, Instant> es, final Map<Long, Instant> ef,
            final Map<Long, Instant> ls, final Map<Long, Instant> lf,
            final Map<Long, TaskSchedule> results, final List<SchedulingWarning> warnings) {
        for (final TaskNode t : leaves) {
            final long id = t.id();
            final WorkingCalendar cal = input.calendarFor(t);
            final long totalFloat = signedWorkedMinutes(cal, es.get(id), ls.get(id));
            final long freeFloat = freeFloat(t, cal, outgoing, es, ef);
            final boolean critical = totalFloat <= EPSILON_MINUTES;
            results.put(id, new TaskSchedule(id, es.get(id), ef.get(id), ls.get(id), lf.get(id),
                    totalFloat, Math.max(0, freeFloat), critical, false));
        }
    }

    private long freeFloat(final TaskNode t, final WorkingCalendar cal,
            final Map<Long, List<DependencyEdge>> outgoing,
            final Map<Long, Instant> es, final Map<Long, Instant> ef) {
        final List<DependencyEdge> outs = outgoing.getOrDefault(t.id(), List.of());
        if (outs.isEmpty()) {
            return 0L; // terminal task: free float folds into total float
        }
        long min = Long.MAX_VALUE;
        for (final DependencyEdge e : outs) {
            final long lag = WorkingCalendar.snapToHour(Math.abs(e.lagMinutes()))
                    * (e.lagMinutes() < 0 ? -1 : 1);
            final Instant succEs = es.get(e.successorId());
            if (succEs == null) {
                continue;
            }
            // slack until the successor's earliest start, in worked minutes, net of lag
            final long gap = signedWorkedMinutes(cal, ef.get(t.id()), succEs) - lag;
            min = Math.min(min, gap);
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    /** Worked minutes between two instants; negative when {@code to} precedes {@code from}. */
    private long signedWorkedMinutes(final WorkingCalendar cal, final Instant from, final Instant to) {
        if (!to.isBefore(from)) {
            return cal.workedMinutesBetween(from, to);
        }
        return -cal.workedMinutesBetween(to, from);
    }

    // ------------------------------------------------------------------ summary aggregation ----

    private void aggregateSummaries(final ScheduleInput input, final Map<Long, TaskNode> byId,
            final Map<Long, TaskSchedule> results) {
        // Children indexed by parent, so summaries aggregate their subtree.
        final Map<Long, List<Long>> children = new HashMap<>();
        for (final TaskNode t : input.tasksInCanonicalOrder()) {
            if (t.parentId() != null) {
                children.computeIfAbsent(t.parentId(), k -> new ArrayList<>()).add(t.id());
            }
        }
        // Process summaries deepest-first so nested recaps see their children's aggregates.
        final List<TaskNode> summaries = new ArrayList<>();
        for (final TaskNode t : input.tasksInCanonicalOrder()) {
            if (t.nodeType() == NodeType.SUMMARY) {
                summaries.add(t);
            }
        }
        summaries.sort(Comparator.comparingInt((TaskNode t) -> depth(t, byId)).reversed()
                .thenComparing(TaskNode::tieBreakKey));
        for (final TaskNode s : summaries) {
            aggregateOne(s, children, results);
        }
    }

    private int depth(final TaskNode t, final Map<Long, TaskNode> byId) {
        int d = 0;
        Long p = t.parentId();
        while (p != null && byId.containsKey(p)) {
            d++;
            p = byId.get(p).parentId();
        }
        return d;
    }

    private void aggregateOne(final TaskNode summary, final Map<Long, List<Long>> children,
            final Map<Long, TaskSchedule> results) {
        Instant minEs = null;
        Instant maxEf = null;
        Instant minLs = null;
        Instant maxLf = null;
        boolean anyCritical = false;
        for (final Long childId : children.getOrDefault(summary.id(), List.of())) {
            final TaskSchedule cs = results.get(childId);
            if (cs == null) {
                continue;
            }
            minEs = minEs == null || cs.earlyStart().isBefore(minEs) ? cs.earlyStart() : minEs;
            maxEf = maxEf == null || cs.earlyFinish().isAfter(maxEf) ? cs.earlyFinish() : maxEf;
            minLs = minLs == null || cs.lateStart().isBefore(minLs) ? cs.lateStart() : minLs;
            maxLf = maxLf == null || cs.lateFinish().isAfter(maxLf) ? cs.lateFinish() : maxLf;
            anyCritical = anyCritical || cs.critical();
        }
        if (minEs == null) {
            return; // empty summary: no aggregate
        }
        results.put(summary.id(), new TaskSchedule(summary.id(), minEs, maxEf, minLs, maxLf,
                0L, 0L, anyCritical, true));
    }

    // ------------------------------------------------------------------ critical path ----------

    private List<Long> criticalPath(final ScheduleInput input, final Map<Long, TaskNode> byId,
            final Map<Long, TaskSchedule> results) {
        final List<Long> path = new ArrayList<>();
        for (final TaskNode t : input.tasksInCanonicalOrder()) {
            final TaskSchedule r = results.get(t.id());
            if (r != null && r.critical() && t.nodeType() != NodeType.SUMMARY) {
                path.add(t.id());
            }
        }
        return path;
    }
}

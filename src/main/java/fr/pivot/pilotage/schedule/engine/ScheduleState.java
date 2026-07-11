package fr.pivot.pilotage.schedule.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The co-editing state passed to and from incremental recompute (EN22.1b) — pairs the current
 * {@link ScheduleInput} with the {@link ScheduleResult} last computed from it. It is the base a
 * {@link ChangeSet} is applied against; its {@link #version()} guards optimistic co-editing.
 *
 * <p>The state also exposes lookups the {@code inverse}/apply machinery needs (task/edge/progress),
 * without leaking mutability of the underlying input.
 */
public final class ScheduleState {

    private final ScheduleInput input;
    private final ScheduleResult result;
    private final Map<Long, Double> progress;

    /**
     * Builds a state from an input and the result computed from it.
     *
     * @param input  the current input snapshot
     * @param result the result computed from {@code input}
     */
    public ScheduleState(final ScheduleInput input, final ScheduleResult result) {
        this(input, result, Map.of());
    }

    /**
     * Builds a state carrying explicit progress values (for {@code SET_PROGRESS} inverses).
     *
     * @param input    the current input snapshot
     * @param result   the result computed from {@code input}
     * @param progress per-task percent-complete values
     */
    public ScheduleState(final ScheduleInput input, final ScheduleResult result,
            final Map<Long, Double> progress) {
        this.input = Objects.requireNonNull(input, "input");
        this.result = Objects.requireNonNull(result, "result");
        this.progress = new HashMap<>(Objects.requireNonNull(progress, "progress"));
    }

    /**
     * Returns the current input snapshot.
     *
     * @return the input
     */
    public ScheduleInput input() {
        return input;
    }

    /**
     * Returns the result computed from the current input.
     *
     * @return the result
     */
    public ScheduleResult result() {
        return result;
    }

    /**
     * Returns the current schedule version (from the result).
     *
     * @return the version
     */
    public long version() {
        return result.scheduleVersion();
    }

    /**
     * Looks up a task node by id.
     *
     * @param taskId the task id
     * @return the task, or {@code null} if absent
     */
    public TaskNode task(final long taskId) {
        for (final TaskNode t : input.tasks()) {
            if (t.id() == taskId) {
                return t;
            }
        }
        return null;
    }

    /**
     * Looks up a task node by id, failing if absent.
     *
     * @param taskId the task id
     * @return the task
     * @throws ScheduleException {@code UNKNOWN_ENTITY} if the task is absent
     */
    public TaskNode taskOrThrow(final long taskId) {
        final TaskNode t = task(taskId);
        if (t == null) {
            throw new ScheduleException(ScheduleErrorCode.UNKNOWN_ENTITY, "unknown task " + taskId);
        }
        return t;
    }

    /**
     * Looks up a dependency edge by its identity triple, failing if absent.
     *
     * @param predecessorId the predecessor id
     * @param successorId   the successor id
     * @param linkType      the link type
     * @return the edge
     * @throws ScheduleException {@code UNKNOWN_ENTITY} if the edge is absent
     */
    public DependencyEdge edgeOrThrow(final long predecessorId, final long successorId,
            final LinkType linkType) {
        for (final DependencyEdge e : input.dependencies()) {
            if (e.predecessorId() == predecessorId && e.successorId() == successorId
                    && e.linkType() == linkType) {
                return e;
            }
        }
        throw new ScheduleException(ScheduleErrorCode.UNKNOWN_ENTITY,
                "unknown edge " + predecessorId + "->" + successorId + "/" + linkType);
    }

    /**
     * Returns the recorded progress for a task, defaulting to 0.
     *
     * @param taskId the task id
     * @return the percent complete
     */
    public double progress(final long taskId) {
        return progress.getOrDefault(taskId, 0.0);
    }

    // ----------------------------------------------------------------- apply a change set -----

    /**
     * Produces a new {@link ScheduleInput} with the change set applied — a pure transformation, no
     * side effect on this state (used by {@code reSchedule} and as the anti-drift oracle input).
     *
     * @param delta the change set to apply
     * @return the new input reflecting the delta
     * @throws ScheduleException {@code UNKNOWN_ENTITY} if an op targets an absent entity
     */
    public ScheduleInput apply(final ChangeSet delta) {
        final Map<Long, TaskNode> tasks = new LinkedHashMap<>();
        for (final TaskNode t : input.tasks()) {
            tasks.put(t.id(), t);
        }
        final List<DependencyEdge> deps = new ArrayList<>(input.dependencies());
        final Map<Long, Double> prog = new HashMap<>(progress);

        for (final ChangeOp op : delta.ops()) {
            applyOne(op, tasks, deps, prog);
        }
        return new ScheduleInput(input.projectId(), input.tenantId(), input.dataDate(),
                input.projectStart(), input.defaultCalendarId(),
                new ArrayList<>(tasks.values()), deps, input.calendars());
    }

    private void applyOne(final ChangeOp op, final Map<Long, TaskNode> tasks,
            final List<DependencyEdge> deps, final Map<Long, Double> prog) {
        // instanceof patterns (not switch patterns) so the code compiles on --release 17 as well
        // as the project's release 24, with no preview features.
        if (op instanceof ChangeOp.AddTask a) {
            tasks.put(a.task().id(), a.task());
        } else if (op instanceof ChangeOp.RemoveTask r) {
            requireTask(tasks, r.taskId());
            tasks.remove(r.taskId());
            deps.removeIf(e -> e.predecessorId() == r.taskId() || e.successorId() == r.taskId());
        } else if (op instanceof ChangeOp.SetDuration d) {
            tasks.put(d.taskId(), with(requireTask(tasks, d.taskId()),
                    b -> b.durationMinutes = d.durationMinutes()));
        } else if (op instanceof ChangeOp.SetMode m) {
            tasks.put(m.taskId(), with(requireTask(tasks, m.taskId()), b -> {
                b.mode = m.mode();
                b.manualStart = m.manualStart();
                b.manualFinish = m.manualFinish();
            }));
        } else if (op instanceof ChangeOp.SetConstraint c) {
            tasks.put(c.taskId(), with(requireTask(tasks, c.taskId()), b -> {
                b.constraintKind = c.kind();
                b.constraintDate = c.date();
            }));
        } else if (op instanceof ChangeOp.SetDeadline dl) {
            tasks.put(dl.taskId(), with(requireTask(tasks, dl.taskId()), b -> b.deadline = dl.deadline()));
        } else if (op instanceof ChangeOp.AddDep ad) {
            deps.add(ad.edge());
        } else if (op instanceof ChangeOp.RemoveDep rd) {
            deps.removeIf(e -> e.predecessorId() == rd.predecessorId()
                    && e.successorId() == rd.successorId() && e.linkType() == rd.linkType());
        } else if (op instanceof ChangeOp.SetLag sl) {
            replaceEdge(deps, sl.predecessorId(), sl.successorId(), sl.linkType(), sl.lagMinutes());
        } else if (op instanceof ChangeOp.SetProgress sp) {
            prog.put(sp.taskId(), sp.percentComplete());
        } else if (op instanceof ChangeOp.MoveWbs mv) {
            tasks.put(mv.taskId(), with(requireTask(tasks, mv.taskId()), b -> {
                b.parentId = mv.newParentId();
                b.wbsPath = mv.newWbsPath();
            }));
        } else if (op instanceof ChangeOp.SetCalendar sc) {
            tasks.put(sc.taskId(), with(requireTask(tasks, sc.taskId()), b -> b.calendarId = sc.calendarId()));
        } else {
            throw new IllegalStateException("unhandled op " + op);
        }
    }

    private TaskNode requireTask(final Map<Long, TaskNode> tasks, final long id) {
        final TaskNode t = tasks.get(id);
        if (t == null) {
            throw new ScheduleException(ScheduleErrorCode.UNKNOWN_ENTITY, "unknown task " + id);
        }
        return t;
    }

    private void replaceEdge(final List<DependencyEdge> deps, final long pred, final long succ,
            final LinkType type, final long lag) {
        for (int i = 0; i < deps.size(); i++) {
            final DependencyEdge e = deps.get(i);
            if (e.predecessorId() == pred && e.successorId() == succ && e.linkType() == type) {
                deps.set(i, new DependencyEdge(e.edgeId(), pred, succ, type, lag));
                return;
            }
        }
        throw new ScheduleException(ScheduleErrorCode.UNKNOWN_ENTITY,
                "unknown edge " + pred + "->" + succ + "/" + type);
    }

    /** Mutable builder-style copy of a {@link TaskNode} for the apply machinery. */
    private static final class Builder {
        private long id;
        private String wbsPath;
        private Long parentId;
        private NodeType nodeType;
        private long durationMinutes;
        private TaskMode mode;
        private long calendarId;
        private ConstraintKind constraintKind;
        private java.time.Instant constraintDate;
        private java.time.Instant deadline;
        private java.time.Instant manualStart;
        private java.time.Instant manualFinish;
    }

    private TaskNode with(final TaskNode base, final java.util.function.Consumer<Builder> mutator) {
        final Builder b = new Builder();
        b.id = base.id();
        b.wbsPath = base.wbsPath();
        b.parentId = base.parentId();
        b.nodeType = base.nodeType();
        b.durationMinutes = base.durationMinutes();
        b.mode = base.mode();
        b.calendarId = base.calendarId();
        b.constraintKind = base.constraintKind();
        b.constraintDate = base.constraintDate();
        b.deadline = base.deadline();
        b.manualStart = base.manualStart();
        b.manualFinish = base.manualFinish();
        mutator.accept(b);
        return new TaskNode(b.id, b.wbsPath, b.parentId, b.nodeType, b.durationMinutes, b.mode,
                b.calendarId, b.constraintKind, b.constraintDate, b.deadline, b.manualStart,
                b.manualFinish);
    }
}

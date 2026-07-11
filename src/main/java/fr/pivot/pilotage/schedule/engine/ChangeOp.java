package fr.pivot.pilotage.schedule.engine;

import java.time.Instant;

/**
 * An atomic change operation in a {@link ChangeSet} (EN22.1b, frozen contract §b). Each op carries
 * the {@code entityId} it targets. The set of ops mirrors the contract:
 * {@code ADD_TASK | REMOVE_TASK | SET_DURATION | SET_MODE | SET_CONSTRAINT | SET_DEADLINE |
 * ADD_DEP | REMOVE_DEP | SET_LAG | SET_PROGRESS | MOVE_WBS | SET_CALENDAR}.
 *
 * <p>Modelled as a sealed interface of records so the applier can pattern-match exhaustively; each
 * op knows how to describe its {@link #inverse(ScheduleState)} for undo/redo.
 */
public sealed interface ChangeOp {

    /**
     * Returns the primary entity id this op targets (task id or dependency edge id).
     *
     * @return the entity id
     */
    long entityId();

    /**
     * Returns the inverse op that undoes this one, given the state before application.
     *
     * @param before the state before this op is applied
     * @return the inverse op
     */
    ChangeOp inverse(ScheduleState before);

    /** Adds a task. */
    record AddTask(TaskNode task) implements ChangeOp {
        @Override public long entityId() {
            return task.id();
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new RemoveTask(task.id());
        }
    }

    /** Removes a task (and its incident edges). */
    record RemoveTask(long taskId) implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new AddTask(before.taskOrThrow(taskId));
        }
    }

    /** Sets a task duration in worked minutes. */
    record SetDuration(long taskId, long durationMinutes) implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new SetDuration(taskId, before.taskOrThrow(taskId).durationMinutes());
        }
    }

    /** Sets a task's scheduling mode. */
    record SetMode(long taskId, TaskMode mode, Instant manualStart, Instant manualFinish)
            implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            final TaskNode t = before.taskOrThrow(taskId);
            return new SetMode(taskId, t.mode(), t.manualStart(), t.manualFinish());
        }
    }

    /** Sets (or clears with a null kind) a task constraint. */
    record SetConstraint(long taskId, ConstraintKind kind, Instant date) implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            final TaskNode t = before.taskOrThrow(taskId);
            return new SetConstraint(taskId, t.constraintKind(), t.constraintDate());
        }
    }

    /** Sets a task's soft deadline. */
    record SetDeadline(long taskId, Instant deadline) implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new SetDeadline(taskId, before.taskOrThrow(taskId).deadline());
        }
    }

    /** Adds a dependency edge. */
    record AddDep(DependencyEdge edge) implements ChangeOp {
        @Override public long entityId() {
            return edge.edgeId();
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new RemoveDep(edge.predecessorId(), edge.successorId(), edge.linkType());
        }
    }

    /** Removes a dependency edge identified by its (pred, succ, type). */
    record RemoveDep(long predecessorId, long successorId, LinkType linkType) implements ChangeOp {
        @Override public long entityId() {
            return successorId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new AddDep(before.edgeOrThrow(predecessorId, successorId, linkType));
        }
    }

    /** Sets the lag of an existing dependency edge. */
    record SetLag(long predecessorId, long successorId, LinkType linkType, long lagMinutes)
            implements ChangeOp {
        @Override public long entityId() {
            return successorId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            final DependencyEdge e = before.edgeOrThrow(predecessorId, successorId, linkType);
            return new SetLag(predecessorId, successorId, linkType, e.lagMinutes());
        }
    }

    /** Sets progress; carried for completeness — progress before the data date pins the task. */
    record SetProgress(long taskId, double percentComplete) implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new SetProgress(taskId, before.progress(taskId));
        }
    }

    /** Moves a task in the WBS (re-parents it), affecting summary rollups. */
    record MoveWbs(long taskId, Long newParentId, String newWbsPath) implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            final TaskNode t = before.taskOrThrow(taskId);
            return new MoveWbs(taskId, t.parentId(), t.wbsPath());
        }
    }

    /** Reassigns a task's calendar. */
    record SetCalendar(long taskId, long calendarId) implements ChangeOp {
        @Override public long entityId() {
            return taskId;
        }

        @Override public ChangeOp inverse(final ScheduleState before) {
            return new SetCalendar(taskId, before.taskOrThrow(taskId).calendarId());
        }
    }
}

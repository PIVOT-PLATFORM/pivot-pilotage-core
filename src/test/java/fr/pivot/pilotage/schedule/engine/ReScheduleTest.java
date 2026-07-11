package fr.pivot.pilotage.schedule.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ScheduleEngine#reSchedule} — the incremental DIFF recompute (EN22.1b): a
 * bounded diff, idempotence, the anti-drift convergence oracle (D3), atomic cycle rejection and
 * stale-base-version handling.
 */
class ReScheduleTest {

    private static final long CAL = 1L;
    private final ScheduleEngine engine = new ScheduleEngine();
    private final WorkingCalendar calendar = WorkingCalendar.standardBusiness(CAL);

    private static Instant at(final int month, final int day, final int hour) {
        return LocalDate.of(2024, month, day).atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant();
    }

    private static final Instant MON_0900 = at(1, 1, 9);

    /** Chain A(1) → B(2) → C(3), each a 1-day leaf. */
    private ScheduleInput chain() {
        final List<TaskNode> tasks = List.of(
                TaskNode.leaf(1, "1", 480, CAL),
                TaskNode.leaf(2, "2", 480, CAL),
                TaskNode.leaf(3, "3", 480, CAL));
        final List<DependencyEdge> deps = List.of(DependencyEdge.fs(1, 2), DependencyEdge.fs(2, 3));
        return new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps, Map.of(CAL, calendar));
    }

    private ScheduleState fresh(final ScheduleInput input) {
        return new ScheduleState(input, engine.schedule(input));
    }

    // -------- AC: empty delta ⇒ empty patch (idempotence D3) ----------------------------------

    @Test
    void reSchedule_emptyDeltaYieldsEmptyPatch() {
        final ScheduleState state = fresh(chain());
        final ScheduleDiff diff = engine.reSchedule(state, new ChangeSet(state.version(), List.of()));
        assertThat(diff.isEmptyPatch()).isTrue();
        assertThat(diff.newCriticalPath()).isNull();
        assertThat(diff.affectedCount()).isZero();
    }

    // -------- AC: reSchedule DIFF is bounded to downstream closure ----------------------------

    @Test
    void reSchedule_diffBoundedToDownstreamClosure() {
        final ScheduleState state = fresh(chain());
        // Lengthen task 2 by one day: task 1 is upstream and must NOT appear; 2 and 3 shift.
        final ChangeSet delta = new ChangeSet(state.version(),
                List.of(new ChangeOp.SetDuration(2, 960)));
        final ScheduleDiff diff = engine.reSchedule(state, delta);

        final List<Long> changed = diff.patches().stream().map(TaskPatch::taskId).toList();
        assertThat(changed).contains(2L, 3L);
        assertThat(changed).doesNotContain(1L); // upstream untouched — a diff, not a snapshot
        assertThat(diff.scheduleVersion()).isEqualTo(state.version() + 1);
    }

    // -------- AC: convergence oracle reSchedule == schedule(apply(input, delta)) (D3) ---------

    @Test
    void reSchedule_convergesWithFullSchedule() {
        final ScheduleState state = fresh(chain());
        final ChangeSet delta = new ChangeSet(state.version(),
                List.of(new ChangeOp.SetDuration(2, 960),
                        new ChangeOp.SetLag(2, 3, LinkType.FS, 60)));

        final ScheduleDiff diff = engine.reSchedule(state, delta);
        final ScheduleInput applied = state.apply(delta);
        final ScheduleResult full = engine.schedule(applied, state.version() + 1);

        // Every patched task matches the full recompute exactly (anti-drift).
        for (final TaskPatch p : diff.patches()) {
            final TaskSchedule expected = full.task(p.taskId());
            if (p.earlyStart() != null) {
                assertThat(p.earlyStart()).isEqualTo(expected.earlyStart());
            }
            if (p.earlyFinish() != null) {
                assertThat(p.earlyFinish()).isEqualTo(expected.earlyFinish());
            }
        }
        // And the diff carries the same version the full recompute would.
        assertThat(diff.scheduleVersion()).isEqualTo(full.scheduleVersion());
    }

    // -------- AC: new critical path emitted only when it changes ------------------------------

    @Test
    void reSchedule_reportsNewCriticalPathOnlyWhenChanged() {
        // Diamond so a branch swap changes the critical path.
        final List<TaskNode> tasks = List.of(
                TaskNode.leaf(1, "1", 480, CAL), TaskNode.leaf(2, "2", 480, CAL),
                TaskNode.leaf(3, "3", 60, CAL), TaskNode.leaf(4, "4", 480, CAL));
        final List<DependencyEdge> deps = List.of(
                DependencyEdge.fs(1, 2), DependencyEdge.fs(1, 3),
                DependencyEdge.fs(2, 4), DependencyEdge.fs(3, 4));
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps,
                Map.of(CAL, calendar));
        final ScheduleState state = fresh(input);
        // Initially branch (1,2,4) is critical (2 is 8h vs 3 is 1h).
        assertThat(state.result().criticalPath()).containsExactly(1L, 2L, 4L);

        // Grow task 3 to 16h so branch (1,3,4) becomes critical instead.
        final ChangeSet delta = new ChangeSet(state.version(),
                List.of(new ChangeOp.SetDuration(3, 960)));
        final ScheduleDiff diff = engine.reSchedule(state, delta);
        assertThat(diff.newCriticalPath()).isNotNull();
        assertThat(diff.newCriticalPath()).containsExactly(1L, 3L, 4L);
    }

    // -------- AC (error): a cycle-introducing delta is rejected whole (atomicity) -------------

    @Test
    void reSchedule_cycleIntroducingDeltaRejectedAtomically() {
        final ScheduleState state = fresh(chain());
        // Add C → A, closing a cycle A→B→C→A.
        final ChangeSet delta = new ChangeSet(state.version(),
                List.of(new ChangeOp.AddDep(DependencyEdge.fs(3, 1))));
        final ScheduleDiff diff = engine.reSchedule(state, delta);

        assertThat(diff.isEmptyPatch()).isTrue();                 // no partial state
        assertThat(diff.scheduleVersion()).isEqualTo(state.version()); // version unchanged
        assertThat(diff.warnings())
                .anyMatch(w -> w.type() == SchedulingWarning.WarningType.REJECTED);
    }

    // -------- AC (error): stale base version --------------------------------------------------

    @Test
    void reSchedule_staleBaseVersionRejected() {
        final ScheduleState state = fresh(chain());
        final ChangeSet delta = new ChangeSet(state.version() + 99,
                List.of(new ChangeOp.SetDuration(2, 960)));
        assertThatThrownBy(() -> engine.reSchedule(state, delta))
                .isInstanceOf(ScheduleException.class)
                .extracting(e -> ((ScheduleException) e).code())
                .isEqualTo(ScheduleErrorCode.STALE_BASE_VERSION);
    }

    // -------- ChangeSet inverse (undo/redo) ---------------------------------------------------

    @Test
    void changeSet_inverseUndoesTheDelta() {
        final ScheduleState state = fresh(chain());
        final ChangeSet delta = new ChangeSet(state.version(),
                List.of(new ChangeOp.SetDuration(2, 960)));
        final ScheduleInput applied = state.apply(delta);
        final ScheduleState afterState = new ScheduleState(applied,
                engine.schedule(applied, state.version() + 1));

        final ChangeSet undo = delta.inverse(state, afterState.version());
        final ScheduleInput restored = afterState.apply(undo);
        // Restoring yields the original schedule (task 2 back to 480 min ⇒ same early finish).
        assertThat(engine.schedule(restored).task(2).earlyFinish())
                .isEqualTo(state.result().task(2).earlyFinish());
    }

    // -------- AddTask / RemoveTask via reSchedule --------------------------------------------

    @Test
    void reSchedule_addAndRemoveTask() {
        final ScheduleState state = fresh(chain());
        final ChangeSet add = new ChangeSet(state.version(),
                List.of(new ChangeOp.AddTask(TaskNode.leaf(4, "4", 480, CAL)),
                        new ChangeOp.AddDep(DependencyEdge.fs(3, 4))));
        final ScheduleDiff addDiff = engine.reSchedule(state, add);
        assertThat(addDiff.patches()).anyMatch(p -> p.taskId() == 4L);

        final ScheduleInput afterAdd = state.apply(add);
        final ScheduleState addedState = new ScheduleState(afterAdd,
                engine.schedule(afterAdd, state.version() + 1));
        final ChangeSet remove = new ChangeSet(addedState.version(),
                List.of(new ChangeOp.RemoveTask(4)));
        final ScheduleDiff removeDiff = engine.reSchedule(addedState, remove);
        assertThat(removeDiff.removed()).containsExactly(4L);
    }
}

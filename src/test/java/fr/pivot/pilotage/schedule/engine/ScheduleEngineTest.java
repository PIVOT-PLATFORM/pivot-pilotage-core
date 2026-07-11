package fr.pivot.pilotage.schedule.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ScheduleEngine} — pure CPM with hand-computed expected values on a known
 * mini-graph (EN22.1b). Calendar: Mon-Fri 09:00-17:00 (8h/day). Anchor: Monday 2024-01-01 09:00
 * UTC (a Monday). Expected early/late/float/critical values are computed by hand at the worked
 * hour, mapping each AC of the frozen contract §b to a test.
 */
class ScheduleEngineTest {

    private static final long CAL = 1L;
    private final ScheduleEngine engine = new ScheduleEngine();
    private final WorkingCalendar calendar = WorkingCalendar.standardBusiness(CAL);

    private static Instant at(final int month, final int day, final int hour) {
        return LocalDate.of(2024, month, day).atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant();
    }

    private static final Instant MON_0900 = at(1, 1, 9);

    /**
     * Diamond graph A → {B, C} → D, each a 1-day (480 min) leaf, all FS lag 0.
     *
     * <pre>
     *   A (Mon) → B (Tue) ─┐
     *          → C (Tue) ─┴→ D (Wed)
     * </pre>
     */
    private ScheduleInput diamond() {
        final List<TaskNode> tasks = List.of(
                TaskNode.leaf(1, "1", 480, CAL),
                TaskNode.leaf(2, "2", 480, CAL),
                TaskNode.leaf(3, "3", 480, CAL),
                TaskNode.leaf(4, "4", 480, CAL));
        final List<DependencyEdge> deps = List.of(
                DependencyEdge.fs(1, 2),
                DependencyEdge.fs(1, 3),
                DependencyEdge.fs(2, 4),
                DependencyEdge.fs(3, 4));
        return new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps,
                Map.of(CAL, calendar));
    }

    // -------- AC: schedule computes ES/EF/LS/LF/float/critical + critical path ----------------

    @Test
    void schedule_computesHandCheckedEarlyLateFloatAndCriticalPath() {
        final ScheduleResult r = engine.schedule(diamond());

        // Forward pass (early dates).
        assertThat(r.task(1).earlyStart()).isEqualTo(at(1, 1, 9));   // Mon 09:00
        assertThat(r.task(1).earlyFinish()).isEqualTo(at(1, 1, 17)); // Mon 17:00
        assertThat(r.task(2).earlyStart()).isEqualTo(at(1, 2, 9));   // Tue 09:00
        assertThat(r.task(2).earlyFinish()).isEqualTo(at(1, 2, 17)); // Tue 17:00
        assertThat(r.task(3).earlyStart()).isEqualTo(at(1, 2, 9));   // Tue 09:00
        assertThat(r.task(4).earlyStart()).isEqualTo(at(1, 3, 9));   // Wed 09:00
        assertThat(r.task(4).earlyFinish()).isEqualTo(at(1, 3, 17)); // Wed 17:00

        // Every task lies on a length-3 path ⇒ zero total float ⇒ all critical.
        assertThat(r.task(1).totalFloatMinutes()).isZero();
        assertThat(r.task(2).totalFloatMinutes()).isZero();
        assertThat(r.task(3).totalFloatMinutes()).isZero();
        assertThat(r.task(4).totalFloatMinutes()).isZero();
        assertThat(r.criticalPath()).containsExactly(1L, 2L, 3L, 4L);

        // Late dates equal early dates on the critical path.
        assertThat(r.task(2).lateStart()).isEqualTo(r.task(2).earlyStart());
        assertThat(r.task(4).lateFinish()).isEqualTo(at(1, 3, 17));
    }

    @Test
    void schedule_nonCriticalTaskCarriesPositiveFloat() {
        // A(1d) → B(1d) → D(1d); A → C(1d) → D. Make C only 1 hour: it gains 7h float on the day.
        final List<TaskNode> tasks = List.of(
                TaskNode.leaf(1, "1", 480, CAL),
                TaskNode.leaf(2, "2", 480, CAL),
                TaskNode.leaf(3, "3", 60, CAL),   // short branch → slack
                TaskNode.leaf(4, "4", 480, CAL));
        final List<DependencyEdge> deps = List.of(
                DependencyEdge.fs(1, 2), DependencyEdge.fs(1, 3),
                DependencyEdge.fs(2, 4), DependencyEdge.fs(3, 4));
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps,
                Map.of(CAL, calendar));

        final ScheduleResult r = engine.schedule(input);
        // The long branch (1,2,4) is critical; C (task 3) has slack and is not.
        assertThat(r.task(1).critical()).isTrue();
        assertThat(r.task(2).critical()).isTrue();
        assertThat(r.task(4).critical()).isTrue();
        assertThat(r.task(3).critical()).isFalse();
        assertThat(r.task(3).totalFloatMinutes()).isGreaterThan(0);
        assertThat(r.criticalPath()).containsExactly(1L, 2L, 4L);
    }

    @Test
    void schedule_honoursStartToStartWithLag() {
        // A → B (SS, lag 60 min): B starts 1 worked hour after A starts.
        final List<TaskNode> tasks = List.of(
                TaskNode.leaf(1, "1", 480, CAL),
                TaskNode.leaf(2, "2", 480, CAL));
        final List<DependencyEdge> deps = List.of(
                new DependencyEdge(0L, 1, 2, LinkType.SS, 60));
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps,
                Map.of(CAL, calendar));

        final ScheduleResult r = engine.schedule(input);
        assertThat(r.task(1).earlyStart()).isEqualTo(at(1, 1, 9));  // Mon 09:00
        assertThat(r.task(2).earlyStart()).isEqualTo(at(1, 1, 10)); // Mon 10:00 (start + 1h)
    }

    // -------- AC: determinism D1 (byte-identical over two runs) -------------------------------

    @Test
    void schedule_isDeterministicAcrossRuns() {
        final ScheduleInput input = diamond();
        final ScheduleResult r1 = engine.schedule(input);
        final ScheduleResult r2 = engine.schedule(input);
        assertThat(r2).isEqualTo(r1);
        assertThat(r2.inputHash()).isEqualTo(r1.inputHash());
        assertThat(r2.computedAt()).isEqualTo(r1.computedAt());
    }

    @Test
    void schedule_isInsensitiveToInsertionOrder() {
        final ScheduleInput ordered = diamond();
        final List<TaskNode> shuffled = new ArrayList<>(ordered.tasks());
        java.util.Collections.reverse(shuffled);
        final List<DependencyEdge> shuffledDeps = new ArrayList<>(ordered.dependencies());
        java.util.Collections.reverse(shuffledDeps);
        final ScheduleInput reordered = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL,
                shuffled, shuffledDeps, Map.of(CAL, calendar));

        assertThat(engine.schedule(reordered).tasks()).isEqualTo(engine.schedule(ordered).tasks());
        assertThat(engine.schedule(reordered).criticalPath())
                .isEqualTo(engine.schedule(ordered).criticalPath());
    }

    // -------- AC: MANUAL pins dates and emits a variance --------------------------------------

    @Test
    void schedule_manualTaskKeepsPinnedDatesAndEmitsVariance() {
        // Task 2 is MANUAL, pinned two days later than its AUTO start (Tue 09:00 → Thu 09:00).
        final TaskNode manualB = new TaskNode(2, "2", null, NodeType.LEAF, 480, TaskMode.MANUAL,
                CAL, null, null, null, at(1, 4, 9), at(1, 4, 17));
        final List<TaskNode> tasks = List.of(
                TaskNode.leaf(1, "1", 480, CAL), manualB);
        final List<DependencyEdge> deps = List.of(DependencyEdge.fs(1, 2));
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps,
                Map.of(CAL, calendar));

        final ScheduleResult r = engine.schedule(input);
        // Pinned dates are not moved.
        assertThat(r.task(2).earlyStart()).isEqualTo(at(1, 4, 9));
        // A variance {plannedManual, wouldBeAuto, delta} is emitted.
        assertThat(r.variances()).hasSize(1);
        final ManualVariance v = r.variances().get(0);
        assertThat(v.taskId()).isEqualTo(2L);
        assertThat(v.plannedManual()).isEqualTo(at(1, 4, 9));
        assertThat(v.wouldBeAuto()).isEqualTo(at(1, 2, 9)); // AUTO would be Tue 09:00
        assertThat(v.deltaMinutes()).isEqualTo(480 * 2);    // 2 worked days later
    }

    // -------- AC: summary aggregation (start=min, finish=max, critical if any leaf critical) ---

    @Test
    void schedule_summaryAggregatesChildren() {
        // Summary S (id 10) over leaves A (id 1) and B (id 2); A → B FS.
        final TaskNode summary = new TaskNode(10, "1", null, NodeType.SUMMARY, 0, TaskMode.AUTO,
                CAL, null, null, null, null, null);
        final TaskNode a = new TaskNode(1, "1.1", 10L, NodeType.LEAF, 480, TaskMode.AUTO,
                CAL, null, null, null, null, null);
        final TaskNode b = new TaskNode(2, "1.2", 10L, NodeType.LEAF, 480, TaskMode.AUTO,
                CAL, null, null, null, null, null);
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL,
                List.of(summary, a, b), List.of(DependencyEdge.fs(1, 2)), Map.of(CAL, calendar));

        final ScheduleResult r = engine.schedule(input);
        assertThat(r.task(10).aggregated()).isTrue();
        assertThat(r.task(10).earlyStart()).isEqualTo(r.task(1).earlyStart()); // min child start
        assertThat(r.task(10).earlyFinish()).isEqualTo(r.task(2).earlyFinish()); // max child finish
        assertThat(r.task(10).critical()).isTrue(); // at least one leaf is critical
        // Summary is not part of the critical-path leaf list.
        assertThat(r.criticalPath()).doesNotContain(10L);
    }

    // -------- AC (error): a cycle is rejected with SCHEDULE_CYCLE, no partial state -----------

    @Test
    void schedule_cycleRejected() {
        final List<TaskNode> tasks = List.of(
                TaskNode.leaf(1, "1", 480, CAL), TaskNode.leaf(2, "2", 480, CAL));
        final List<DependencyEdge> deps = List.of(
                DependencyEdge.fs(1, 2), DependencyEdge.fs(2, 1));
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps,
                Map.of(CAL, calendar));

        assertThatThrownBy(() -> engine.schedule(input))
                .isInstanceOf(ScheduleException.class)
                .extracting(e -> ((ScheduleException) e).code())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_CYCLE);
    }

    // -------- AC (error): unknown calendar --------------------------------------------------

    @Test
    void schedule_unknownCalendarRejected() {
        final List<TaskNode> tasks = List.of(TaskNode.leaf(1, "1", 480, 999L));
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, 999L, tasks,
                List.of(), Map.of()); // no calendar at all
        assertThatThrownBy(() -> engine.schedule(input))
                .isInstanceOf(ScheduleException.class)
                .extracting(e -> ((ScheduleException) e).code())
                .isEqualTo(ScheduleErrorCode.UNKNOWN_CALENDAR);
    }

    // -------- AC (security): dependency crossing the snapshot is a tenant violation ----------

    @Test
    void schedule_dependencyOutsideSnapshotRejected() {
        final List<TaskNode> tasks = List.of(TaskNode.leaf(1, "1", 480, CAL));
        final List<DependencyEdge> deps = List.of(DependencyEdge.fs(1, 2)); // task 2 absent
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL, tasks, deps,
                Map.of(CAL, calendar));
        assertThatThrownBy(() -> engine.schedule(input))
                .isInstanceOf(ScheduleException.class)
                .extracting(e -> ((ScheduleException) e).code())
                .isEqualTo(ScheduleErrorCode.TENANT_VIOLATION);
    }

    // -------- AC: SNET constraint floors the early start; deadline miss warns ----------------

    @Test
    void schedule_snetConstraintFloorsStartAndDeadlineMissWarns() {
        // Task with SNET = Wed 09:00 and a deadline of Wed 12:00 it will miss (finishes Wed 17:00).
        final TaskNode t = new TaskNode(1, "1", null, NodeType.LEAF, 480, TaskMode.AUTO, CAL,
                ConstraintKind.SNET, at(1, 3, 9), at(1, 3, 12), null, null);
        final ScheduleInput input = new ScheduleInput(100L, 7L, MON_0900, MON_0900, CAL,
                List.of(t), List.of(), Map.of(CAL, calendar));

        final ScheduleResult r = engine.schedule(input);
        assertThat(r.task(1).earlyStart()).isEqualTo(at(1, 3, 9)); // floored to Wed 09:00
        assertThat(r.warnings()).anyMatch(
                w -> w.type() == SchedulingWarning.WarningType.DEADLINE_MISSED && w.taskId() == 1L);
    }
}

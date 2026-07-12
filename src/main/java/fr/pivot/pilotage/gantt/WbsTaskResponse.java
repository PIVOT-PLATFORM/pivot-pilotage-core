package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.NodeKind;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for one node of a project's WBS tree (US22.4.1a/b/c, extended US22.4.7 critical
 * path/slack, US22.4.8 progress line) — never the JPA entity directly (CLAUDE.md §Standards).
 * Returned flattened in a pre-order (depth-first) traversal by {@link WbsTaskService#tree}; the
 * {@link #ariaLevel}/{@link #ariaSetSize}/{@link #ariaPosInSet} attributes let the future
 * {@code pivot-pilotage-ui} widget render a {@code role="tree"} directly from this payload without
 * recomputing the hierarchy client-side.
 *
 * <p><strong>Summary vs. leaf (US22.4.1c).</strong> When {@link #nodeKind} is {@code SUMMARY} the
 * temporal fields ({@link #startDate}, {@link #finishDate}, {@link #durationMinutes},
 * {@link #percentComplete}) carry the <em>aggregated</em> rollup of the summary's descendants
 * (start=min, finish=max, duration/work=Σ, percent=charge-weighted mean) and {@link #readOnly} is
 * {@code true}: these fields are derived and may never be edited directly (a direct edit is
 * rejected {@code 422}). For a leaf/milestone they are the node's own values and {@link #readOnly}
 * is {@code false}. {@link #ariaReadOnly} mirrors {@link #readOnly} for screen-reader exposure, and
 * {@link #progressLabel} carries a textual progress rendering so completion never relies on colour
 * alone (A11y AC).
 *
 * <p><strong>Progress line (US22.4.8 AC).</strong> {@link #expectedPercentComplete} is where the
 * node's percent complete <em>should</em> be by the project's status date (linear interpolation
 * over {@link #startDate}/{@link #finishDate}), and {@link #late} is whether the actual
 * {@link #percentComplete} falls short of it — together they let the frontend materialise the
 * "progress line" (MS-Project-style) connecting every late task at the status date.
 * {@link #progressVarianceLabel} carries a textual rendering of that variance (e.g.
 * {@code "3d late"}, {@code "on track"}), again never colour-only (A11y AC). All three are
 * {@code null}/{@code false} when the node has no schedule (dates) or the project has no status
 * date yet — the line does not apply.
 *
 * <p><strong>Critical path &amp; slack (US22.4.7).</strong> {@link #isCritical}, {@link
 * #totalSlackMinutes} and {@link #freeSlackMinutes} are read-only exposure of the CPM columns
 * already computed and persisted by the EN22.1b engine ({@code SchedulingService}) — nothing is
 * (re)computed here. A write attempt on any of them is rejected {@code 422} by
 * {@link WbsExceptionHandler}. A leaf/milestone carries its own engine-derived values; a summary
 * rolls up {@link #isCritical} (true if at least one descendant leaf is critical — the same
 * {@code SummaryAggregate#critical} semantics as the other aggregated fields), but {@link
 * #totalSlackMinutes}/{@link #freeSlackMinutes} stay {@code null} on a summary: "float of a rollup"
 * has no CPM meaning, and {@code SummaryAggregate} (EN22.1c, frozen contract §c) deliberately does
 * not define it. {@link #criticalLabel} carries a textual alternative to the boolean flag so the
 * Gantt's critical-path highlighting never relies on colour alone (A11y AC), the same pattern as
 * {@link #progressLabel}; it is {@code null} exactly when {@link #isCritical} is (not yet scheduled).
 * The fractionnement (task split) AC of the parent US is explicitly out of scope here — Sprint 10
 * Gate 1 READINESS reserve D1: no segment representation exists in the EN22.1 schema.
 *
 * @param taskId                  stable task id
 * @param parentTaskId            WBS parent task id, or {@code null} at the root
 * @param wbsCode                 server-derived WBS code ({@code 1}, {@code 1.2.3})
 * @param name                    task name
 * @param nodeKind                kind of node (summary / leaf / milestone / recurring)
 * @param position                display order among its siblings
 * @param startDate               start (own for a leaf, aggregated min for a summary), or
 *                                {@code null}
 * @param finishDate              finish (own for a leaf, aggregated max for a summary), or
 *                                {@code null}
 * @param durationMinutes         duration (own for a leaf, aggregated Σ for a summary), or
 *                                {@code null}
 * @param percentComplete         percent complete (own for a leaf, weighted mean for a summary), or
 *                                {@code null}
 * @param progressLabel           textual progress rendering (e.g. {@code "45%"}), never
 *                                colour-only (A11y)
 * @param expectedPercentComplete where the percent complete should be by the project's status date
 *                                (progress line, US22.4.8), or {@code null} if not applicable
 * @param late                    whether {@code percentComplete} falls short of
 *                                {@code expectedPercentComplete} at the status date (US22.4.8)
 * @param progressVarianceLabel   textual rendering of the progress-line variance (e.g.
 *                                {@code "3d late"}, {@code "on track"}), never colour-only (A11y),
 *                                or {@code null} if not applicable
 * @param isCritical              engine-derived critical-path flag ({@code totalFloat <= 0});
 *                                rolled up (any-child) for a summary; {@code null} if not yet
 *                                scheduled
 * @param totalSlackMinutes       engine-derived total float in worked minutes (own value);
 *                                {@code null} for a summary (no rollup defined) or if not yet
 *                                scheduled
 * @param freeSlackMinutes        engine-derived free float in worked minutes (own value);
 *                                {@code null} for a summary (no rollup defined) or if not yet
 *                                scheduled
 * @param criticalLabel           textual alternative to {@link #isCritical} (e.g.
 *                                {@code "Critical"}), never colour-only (A11y); {@code null} iff
 *                                {@link #isCritical} is
 * @param readOnly                whether the derived fields are read-only (always {@code true} for
 *                                a summary)
 * @param ariaRole                ARIA role for the tree widget ({@code "treeitem"})
 * @param ariaLevel               1-based depth in the tree (A11y {@code aria-level})
 * @param ariaSetSize             number of siblings sharing this node's parent (A11y
 *                                {@code aria-setsize})
 * @param ariaPosInSet            1-based rank among those siblings (A11y {@code aria-posinset})
 * @param ariaReadOnly            A11y {@code aria-readonly}, mirrors {@link #readOnly}
 * @param nodeKindLabel           stable, human-readable text label for {@link #nodeKind} (e.g.
 *                                {@code "Milestone"}, {@code "Recurring task series"}) — US22.4.6
 *                                A11y AC: a milestone's diamond glyph and a periodic task's
 *                                occurrences must be identifiable by an accessible text label,
 *                                never shape/colour alone
 * @param revision                monotonic revision — optimistic co-editing lock and event ordering
 */
public record WbsTaskResponse(
        long taskId,
        Long parentTaskId,
        String wbsCode,
        String name,
        NodeKind nodeKind,
        int position,
        Instant startDate,
        Instant finishDate,
        Integer durationMinutes,
        BigDecimal percentComplete,
        String progressLabel,
        BigDecimal expectedPercentComplete,
        boolean late,
        String progressVarianceLabel,
        Boolean isCritical,
        Integer totalSlackMinutes,
        Integer freeSlackMinutes,
        String criticalLabel,
        boolean readOnly,
        String ariaRole,
        int ariaLevel,
        int ariaSetSize,
        int ariaPosInSet,
        boolean ariaReadOnly,
        String nodeKindLabel,
        int revision) {

    /** ARIA role of a WBS tree node — a {@code treeitem} inside the widget's {@code role="tree"}. */
    public static final String ARIA_ROLE_TREEITEM = "treeitem";

    /**
     * Maps a {@link NodeKind} to its stable, human-readable A11y text label (US22.4.6 AC) — kept
     * here, next to the field it fills, so every producer of a {@link WbsTaskResponse}
     * ({@code WbsTaskService}, {@code RecurringTaskService}) derives the identical label rather than
     * re-inventing a kind-to-text mapping.
     *
     * @param nodeKind the node kind
     * @return the accessible label
     */
    public static String labelFor(final NodeKind nodeKind) {
        return switch (nodeKind) {
            case SUMMARY -> "Summary task";
            case LEAF -> "Task";
            case MILESTONE -> "Milestone";
            case RECURRING -> "Recurring task series";
        };
    }
}

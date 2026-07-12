package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.NodeKind;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for one node of a project's WBS tree (US22.4.1a/b/c) — never the JPA entity directly
 * (CLAUDE.md §Standards). Returned flattened in a pre-order (depth-first) traversal by
 * {@link WbsTaskService#tree}; the {@link #ariaLevel}/{@link #ariaSetSize}/{@link #ariaPosInSet}
 * attributes let the future {@code pivot-pilotage-ui} widget render a {@code role="tree"} directly
 * from this payload without recomputing the hierarchy client-side.
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
 * @param taskId          stable task id
 * @param parentTaskId    WBS parent task id, or {@code null} at the root
 * @param wbsCode         server-derived WBS code ({@code 1}, {@code 1.2.3})
 * @param name            task name
 * @param nodeKind        kind of node (summary / leaf / milestone / recurring)
 * @param position        display order among its siblings
 * @param startDate       start (own for a leaf, aggregated min for a summary), or {@code null}
 * @param finishDate      finish (own for a leaf, aggregated max for a summary), or {@code null}
 * @param durationMinutes duration (own for a leaf, aggregated Σ for a summary), or {@code null}
 * @param percentComplete percent complete (own for a leaf, weighted mean for a summary), or
 *                        {@code null}
 * @param progressLabel   textual progress rendering (e.g. {@code "45%"}), never colour-only (A11y)
 * @param readOnly        whether the derived fields are read-only (always {@code true} for a
 *                        summary)
 * @param ariaRole        ARIA role for the tree widget ({@code "treeitem"})
 * @param ariaLevel       1-based depth in the tree (A11y {@code aria-level})
 * @param ariaSetSize     number of siblings sharing this node's parent (A11y {@code aria-setsize})
 * @param ariaPosInSet    1-based rank among those siblings (A11y {@code aria-posinset})
 * @param ariaReadOnly    A11y {@code aria-readonly}, mirrors {@link #readOnly}
 * @param nodeKindLabel   stable, human-readable text label for {@link #nodeKind} (e.g.
 *                        {@code "Milestone"}, {@code "Recurring task series"}) — US22.4.6 A11y AC:
 *                        a milestone's diamond glyph and a periodic task's occurrences must be
 *                        identifiable by an accessible text label, never shape/colour alone
 * @param revision        monotonic revision — optimistic co-editing lock and event ordering
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

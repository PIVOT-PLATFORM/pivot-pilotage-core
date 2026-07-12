package fr.pivot.pilotage.gantt;

import java.util.List;

/**
 * Response DTO of {@code POST .../gantt/tasks/recurring} (US22.4.6) &mdash; never the JPA entity
 * directly (CLAUDE.md &sect;Standards). Carries the created series task and every generated
 * occurrence, each rendered through the same {@link WbsTaskResponse} the WBS tree exposes (reuse,
 * not reinvention &mdash; Étape&nbsp;0: an occurrence is a plain {@code Task} row under the series, so
 * it gets the identical server-derived WBS code / ARIA attributes / A11y
 * {@link WbsTaskResponse#nodeKindLabel()} as any other tree node, computed once via
 * {@code WbsTaskService.tree}).
 *
 * <p>Takes a defensive, unmodifiable copy of {@link #occurrences} (SpotBugs {@code EI_EXPOSE_REP}).
 *
 * @param series         the created recurring series ({@code node_kind=RECURRING})
 * @param recurrenceRule the persisted iCalendar-style rule
 *                        ({@code FREQ=...;INTERVAL=...;COUNT=...;DTSTART=...})
 * @param occurrences    the generated occurrences, in chronological order
 */
public record RecurringTaskResponse(WbsTaskResponse series, String recurrenceRule, List<WbsTaskResponse> occurrences) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the occurrence list.
     */
    public RecurringTaskResponse {
        occurrences = List.copyOf(occurrences);
    }
}

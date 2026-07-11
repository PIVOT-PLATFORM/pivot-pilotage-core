package fr.pivot.pilotage.gantt;

/**
 * Request body of {@code PATCH .../gantt/tasks/{taskId}/move} (US22.4.1b) — the single structural
 * mutation backing indent, outdent and reordering. Every field is optional; the controller maps
 * dedicated verbs (indent/outdent/reorder) onto this one payload so the frontend can also send a
 * combined move in one call.
 *
 * <p><strong>{@code wbsCode} is deliberately absent</strong> — it is derived server-side and
 * recomputed by the engine after the move (US22.4.1a), never client-supplied.
 *
 * @param parentTaskId the new WBS parent task id, or {@code null} to leave the parent unchanged;
 *                     the sentinel {@link #ROOT} moves the task to the WBS root (outdent to root)
 * @param position     the new display order among the (new) siblings, or {@code null} to leave the
 *                     position unchanged
 */
public record MoveWbsTaskRequest(Long parentTaskId, Integer position) {

    /**
     * Sentinel value for {@link #parentTaskId} requesting a move to the WBS root (no parent). A
     * plain {@code null} means "leave the parent unchanged", so an explicit root move needs a
     * distinct, out-of-range marker rather than {@code null}.
     */
    public static final long ROOT = -1L;

    /**
     * Returns whether this request asks to reparent the task (including a move to root).
     *
     * @return {@code true} if {@link #parentTaskId} is set (root sentinel included)
     */
    public boolean reparents() {
        return parentTaskId != null;
    }

    /**
     * Returns whether the requested reparent targets the WBS root.
     *
     * @return {@code true} if {@link #parentTaskId} equals {@link #ROOT}
     */
    public boolean toRoot() {
        return parentTaskId != null && parentTaskId == ROOT;
    }
}

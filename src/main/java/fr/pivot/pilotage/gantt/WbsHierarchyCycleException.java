package fr.pivot.pilotage.gantt;

/**
 * Thrown when a WBS structural mutation would make a task its own ancestor — a cycle in the
 * <strong>hierarchy</strong> graph ({@code parent_task_id}), e.g. reparenting a task under one of
 * its own descendants (US22.4.1a error AC).
 *
 * <p><strong>Design decision D4 (Gate 1).</strong> This is mapped to {@code 409 Conflict} — the
 * error AC of US22.4.1a mandates {@code 409} for a WBS-hierarchy cycle. It is intentionally
 * distinct from the engine's {@code SCHEDULE_CYCLE} ({@code fr.pivot.pilotage.schedule.engine}),
 * which concerns cycles in the <em>dependency</em> graph (predecessor/successor links), a different
 * relation validated later by the CPM engine. A hierarchy cycle is caught <em>before</em> delegating
 * to the engine: {@code WbsNumbering.derive} recurses over {@code parent_task_id}, so a cycle there
 * would never even reach the engine — it must be rejected up front by this service.
 */
public class WbsHierarchyCycleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a reparent that would create a hierarchy cycle.
     *
     * @param taskId      the task being moved
     * @param newParentId the requested new parent that is itself a descendant of {@code taskId}
     */
    public WbsHierarchyCycleException(final long taskId, final long newParentId) {
        super("Moving task " + taskId + " under " + newParentId
                + " would create a WBS hierarchy cycle (task referenced as its own ancestor)");
    }
}

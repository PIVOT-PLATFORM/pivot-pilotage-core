package fr.pivot.pilotage.gantt;

import java.util.List;

/**
 * Response DTO of {@code GET .../gantt/tree} (US22.4.1a) — the whole WBS of a project, flattened in
 * pre-order (depth-first, siblings by position) so the frontend can render it as a
 * {@code role="tree"} list directly, and carrying the ARIA container role for that widget.
 *
 * <p>Takes a defensive, unmodifiable copy of {@link #nodes} (SpotBugs {@code EI_EXPOSE_REP}).
 *
 * @param projectId the owning project id
 * @param ariaRole  ARIA role for the tree container ({@code "tree"})
 * @param nodes     the WBS nodes in pre-order (never {@code null})
 */
public record WbsTreeResponse(long projectId, String ariaRole, List<WbsTaskResponse> nodes) {

    /** ARIA role of the container element wrapping the WBS nodes. */
    public static final String ARIA_ROLE_TREE = "tree";

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the node list.
     */
    public WbsTreeResponse {
        nodes = List.copyOf(nodes);
    }

    /**
     * Builds a tree response for a project from its pre-ordered nodes.
     *
     * @param projectId the owning project id
     * @param nodes     the WBS nodes in pre-order
     * @return the wrapped response with the {@code role="tree"} container role
     */
    static WbsTreeResponse of(final long projectId, final List<WbsTaskResponse> nodes) {
        return new WbsTreeResponse(projectId, ARIA_ROLE_TREE, nodes);
    }
}

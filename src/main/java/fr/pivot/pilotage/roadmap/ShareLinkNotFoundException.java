package fr.pivot.pilotage.roadmap;

/**
 * Thrown when the <strong>authenticated</strong> share-link management endpoint (US22.3.5,
 * {@code DELETE .../roadmap/share-links/{shareLinkId}}) targets a share link id that does not
 * resolve under the given project/tenant/team — unknown id, or a real id belonging to a
 * different project/tenant/team.
 *
 * <p>Mapped to a bodyless 404 by {@link RoadmapShareExceptionHandler} — same non-disclosure
 * posture as {@link ProjectNotFoundException}/{@link InitiativeNotFoundException} (CLAUDE.md
 * §Isolation tenant): an authorized editor of project A must not be able to distinguish "no such
 * link" from "that link belongs to project B" by probing ids.
 *
 * <p>Deliberately distinct from {@link ShareLinkAccessDeniedException}, used on the
 * <strong>public</strong> consultation path: this exception only ever fires for an authenticated
 * caller managing their own project's links, never for a share-link recipient.
 */
public class ShareLinkNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a share link not visible under the given project.
     *
     * @param shareLinkId the {@code pilotage.roadmap_share_link.id} that was not found
     * @param projectId   the project the link was expected to belong to
     */
    public ShareLinkNotFoundException(final long shareLinkId, final long projectId) {
        super("No share link " + shareLinkId + " on project " + projectId);
    }
}

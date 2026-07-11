package fr.pivot.pilotage.roadmap;

/**
 * Thrown by the <strong>public</strong>, unauthenticated roadmap share view (US22.3.5,
 * {@code GET /public/roadmap-shares/{token}}) whenever the supplied token does not currently
 * grant read-only access — covering three distinct causes, deliberately collapsed into one:
 *
 * <ul>
 *   <li>the token never matched any share link (unknown/mistyped/tampered token);</li>
 *   <li>the matching link was explicitly revoked ({@link RoadmapShareLink#getRevokedAt()});</li>
 *   <li>the matching link's optional expiry has passed ({@link RoadmapShareLink#getExpiresAt()}).</li>
 * </ul>
 *
 * <p><strong>Deliberately a single exception/message for all three</strong> — mirrors
 * {@link ProjectNotFoundException}'s reasoning for collapsing "unknown tenant" / "unknown team" /
 * "cross-team project" into one 404: telling a recipient <em>which</em> of the three applies
 * would let an attacker probing tokens distinguish "this token never existed" from "this token
 * existed and is now revoked", leaking information about the sharing history of a roadmap they
 * are not authorized to see. Mapped to <strong>404 with an {@link ApiError} body</strong> by
 * {@link RoadmapShareExceptionHandler} — unlike the bodyless 404s used elsewhere for tenant/team
 * isolation, the AC here explicitly requires an explicit, human-readable message ("l'accès est
 * refusé avec un message explicite"), so a body is required; the single shared message satisfies
 * that AC without disclosing internal state.
 */
public class ShareLinkAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Builds the exception with the single, non-disclosing, caller-facing message. */
    public ShareLinkAccessDeniedException() {
        super("Ce lien de partage est invalide, expiré ou a été révoqué.");
    }
}

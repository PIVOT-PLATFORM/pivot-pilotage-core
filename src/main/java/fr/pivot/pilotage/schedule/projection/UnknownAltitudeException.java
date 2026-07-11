package fr.pivot.pilotage.schedule.projection;

/**
 * Raised when a projection request carries an unrecognised {@code altitude} token (EN22.1c, frozen
 * contract §c — AC error case {@code GET /plan?altitude=INCONNUE}).
 *
 * <p>The (deferred, post-starter) controller maps this to HTTP <strong>422</strong> (unprocessable
 * entity): no partial projection is produced. Kept in the service layer so the mapping rule lives
 * next to the domain, not the transport.
 */
public class UnknownAltitudeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for an unrecognised token.
     *
     * @param raw the offending token (may be {@code null})
     */
    public UnknownAltitudeException(final String raw) {
        super("unknown altitude: " + raw);
    }
}

package fr.pivot.pilotage.roadmap;

/**
 * Thrown when creating a share link (US22.3.5, {@code POST .../roadmap/share-links}) with an
 * {@code expiresAt} that is not strictly in the future — a share link created already-expired
 * would be a silently useless (and confusing) resource.
 *
 * <p>Mapped to 400 with an {@link ApiError} body by {@link RoadmapShareExceptionHandler} —
 * mirrors {@link InvalidInitiativePeriodException}'s reasoning: not itself an explicit AC of the
 * backlog Gate 1 file, but a minimal sanity guard a PO/Architect call for this under-specified
 * mechanism, costing nothing and preventing a nonsensical row from ever being persisted.
 */
public class InvalidShareLinkExpiryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception with a caller-facing message.
     *
     * @param message the reason the expiry is rejected
     */
    public InvalidShareLinkExpiryException(final String message) {
        super(message);
    }
}

package fr.pivot.pilotage.roadmap;

/**
 * Thrown when creating a lane (US22.3.1, {@code POST .../roadmap/lanes}) would duplicate the
 * label of a lane that already exists on the same project (case-insensitive) — two lanes with the
 * same label would be ambiguous for the user placing an initiative ("which lane receives it?").
 *
 * <p>Mapped to <strong>409 Conflict</strong> with an {@link ApiError} body by
 * {@link RoadmapExceptionHandler} — a deliberate rejection rather than a silent
 * create-or-reuse, so the caller always knows which lane id it is placing initiatives on.
 */
public class DuplicateLaneNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Builds the exception for a duplicate lane label.
     *
     * @param name      the label that already exists on the project
     * @param projectId the project the lane belongs to
     */
    public DuplicateLaneNameException(final String name, final long projectId) {
        super("Lane '" + name + "' already exists on project " + projectId);
    }
}

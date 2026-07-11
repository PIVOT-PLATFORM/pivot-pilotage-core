package fr.pivot.pilotage.gantt;

/**
 * Thrown when an indent/outdent is not applicable to the target task (US22.4.1b error AC):
 * <ul>
 *   <li><strong>indent of the first task</strong> — the very first task of the plan has no
 *       preceding sibling to become a child of, so no parent is possible;</li>
 *   <li><strong>outdent at the root level</strong> — a task already at the WBS root cannot be
 *       lifted any higher.</li>
 * </ul>
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} by {@link WbsExceptionHandler} with an explicit
 * caller-facing message (AC: "refusée avec un message explicite"). Validated server-side, never
 * merely hidden client-side, so a direct API caller is rejected too.
 */
public class IllegalWbsMoveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for an illegal indent/outdent. */
    public static final String CODE = "ILLEGAL_WBS_MOVE";

    /**
     * Builds the exception with the given caller-facing message.
     *
     * @param message the explicit reason (why the indent/outdent is not applicable)
     */
    public IllegalWbsMoveException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for indenting the first task of the plan (no possible parent).
     *
     * @param taskId the first task that cannot be indented
     * @return the exception
     */
    public static IllegalWbsMoveException indentFirstTask(final long taskId) {
        return new IllegalWbsMoveException("Cannot indent task " + taskId
                + ": it is the first task among its siblings and has no preceding sibling to nest under");
    }

    /**
     * Builds the exception for outdenting a task already at the WBS root.
     *
     * @param taskId the root-level task that cannot be outdented
     * @return the exception
     */
    public static IllegalWbsMoveException outdentRoot(final long taskId) {
        return new IllegalWbsMoveException("Cannot outdent task " + taskId
                + ": it is already at the WBS root level");
    }
}

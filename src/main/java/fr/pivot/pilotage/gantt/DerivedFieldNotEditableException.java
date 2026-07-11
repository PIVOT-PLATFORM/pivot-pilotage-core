package fr.pivot.pilotage.gantt;

/**
 * Thrown when a client tries to write a <strong>derived</strong> field directly (US22.4.1a/c error
 * ACs):
 * <ul>
 *   <li>supplying a {@code wbsCode} on task creation — the WBS code is derived server-side by the
 *       engine (US22.4.1a), never client-written;</li>
 *   <li>editing a summary task's aggregated dates/duration/percent-complete — those are the rollup
 *       of the summary's descendants (US22.4.1c) and are read-only.</li>
 * </ul>
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} by {@link WbsExceptionHandler} with an explicit
 * message explaining the field is derived.
 */
public class DerivedFieldNotEditableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for a rejected derived-field write. */
    public static final String CODE = "DERIVED_FIELD_NOT_EDITABLE";

    /**
     * Builds the exception with the given caller-facing message.
     *
     * @param message the explicit reason (which derived field, and why it is not editable)
     */
    public DerivedFieldNotEditableException(final String message) {
        super(message);
    }

    /**
     * Builds the exception for a client-supplied {@code wbsCode} on creation.
     *
     * @return the exception
     */
    public static DerivedFieldNotEditableException clientSuppliedWbsCode() {
        return new DerivedFieldNotEditableException("wbsCode is derived server-side by the "
                + "scheduling engine (parent + rank) and must not be supplied by the client");
    }

    /**
     * Builds the exception for a direct edit of a summary task's aggregated field.
     *
     * @param taskId the summary task whose derived field was targeted
     * @param field  the derived field name (e.g. {@code startDate})
     * @return the exception
     */
    public static DerivedFieldNotEditableException summaryField(final long taskId, final String field) {
        return new DerivedFieldNotEditableException("Field '" + field + "' of summary task " + taskId
                + " is derived (aggregated from its sub-tasks) and cannot be edited directly");
    }
}

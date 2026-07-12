package fr.pivot.pilotage.baseline;

/**
 * Thrown when posing a baseline without an explicit {@code baselineIndex} would exceed the MS
 * Project parity limit of 11 baselines per project (0..10 — US22.4.9 error AC): "given une tentative
 * de poser une 12e baseline (au-delà de la limite de 11), then le système refuse et invite à écraser
 * ou supprimer une baseline existante".
 *
 * <p>Mapped to {@code 409 Conflict} by {@link BaselineExceptionHandler} — a conflict with existing
 * state (every slot already used), distinct from the {@code 422} value-validation error
 * ({@link InvalidBaselineIndexException}). Never thrown when the caller supplies an explicit,
 * already-used index: that path is the "écraser" (overwrite) action instead, handled by
 * {@link BaselineService#setBaseline}.
 */
public class BaselineLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable error code for the error body. */
    public static final String CODE = "BASELINE_LIMIT_EXCEEDED";

    /**
     * Builds the exception for a project whose 11 baseline slots are all already used.
     *
     * @param projectId the project id
     */
    public BaselineLimitExceededException(final long projectId) {
        super("Project " + projectId + " already has the maximum of 11 baselines (indices 0..10, "
                + "MS Project parity: Baseline + Baseline 1..10); overwrite an existing baseline "
                + "(POST with an explicit baselineIndex) or delete one first "
                + "(DELETE .../baselines/{baselineIndex})");
    }
}

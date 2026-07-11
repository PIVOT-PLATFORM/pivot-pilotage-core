package fr.pivot.pilotage.roadmap;

import java.time.LocalDate;

/**
 * Thrown when a strategic milestone (US22.3.4) is created or moved with no usable date: either no
 * {@code date} at all ({@link #missing(long)} — AC "Error: given a milestone without a date... the
 * action is rejected with an explicit message"), or a {@code date} outside the project's bounds
 * ({@link #outOfBounds(LocalDate, long, LocalDate, LocalDate)}).
 *
 * <p><strong>PO Agent clarification of an ambiguous AC (Gate 1, documented per CLAUDE.md
 * §Breaking&nbsp;Points "AC ambigu à l'implémentation").</strong> The backlog Gate 1 file asks to
 * reject a date "hors des bornes du projet" but — verified against both {@code Project}
 * ({@code fr.pivot.pilotage.project.Project}: no start/end date column, only a nullable
 * {@code status_date} freshness anchor) and every table in {@code V1__schema_init.sql} — the
 * schema carries <strong>no explicit project-level date range</strong>. Rather than inventing one
 * (a new {@code Project} column would itself be a schema change requiring maintainer green light
 * pre-BETA, CLAUDE.md §Migrations Flyway, and would conflict with the additive-only footprint asked
 * of this US while sibling US22.3.3/US22.3.5 touch this same repo in parallel), the bounds are
 * derived from the project's <em>existing</em> temporal footprint: the envelope (earliest
 * effective start, latest effective end) of every other {@code Task} row already scheduled on the
 * project — reading whichever of the fuzzy period ({@code fuzzy_period_start}/
 * {@code fuzzy_period_end}) or the precise Gantt dates ({@code start_date}/{@code finish_date}) is
 * populated on each row. A project with no other dated task yet has no bounds to violate — only
 * the "date is required" check applies, so a brand-new project's first milestone is never
 * rejected. See {@code RoadmapService#requireWithinProjectBounds} for the computation.
 *
 * <p>Mapped to 400 with an {@link ApiError} body by {@link RoadmapExceptionHandler} — same
 * contract shape as {@link LaneNotFoundException} (distinct machine-readable codes for the two
 * failure modes, both caller-facing).
 */
public class InvalidMilestoneDateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Machine-readable code for the "no date at all" case. */
    static final String CODE_REQUIRED = "MILESTONE_DATE_REQUIRED";

    /** Machine-readable code for the "date outside the project's known bounds" case. */
    static final String CODE_OUT_OF_BOUNDS = "MILESTONE_DATE_OUT_OF_BOUNDS";

    private final String code;

    private InvalidMilestoneDateException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Builds the exception for a request that supplied no date at all.
     *
     * @param projectId the project the milestone was being created on
     * @return the exception, carrying {@link #CODE_REQUIRED}
     */
    public static InvalidMilestoneDateException missing(final long projectId) {
        return new InvalidMilestoneDateException(CODE_REQUIRED,
                "A date is required to create a milestone on project " + projectId);
    }

    /**
     * Builds the exception for a date outside the project's derived bounds.
     *
     * @param date       the rejected date
     * @param projectId  the project the milestone was being placed on
     * @param lowerBound the earliest date already scheduled on the project, or {@code null}
     * @param upperBound the latest date already scheduled on the project, or {@code null}
     * @return the exception, carrying {@link #CODE_OUT_OF_BOUNDS}
     */
    public static InvalidMilestoneDateException outOfBounds(final LocalDate date, final long projectId,
            final LocalDate lowerBound, final LocalDate upperBound) {
        return new InvalidMilestoneDateException(CODE_OUT_OF_BOUNDS,
                "Milestone date " + date + " is outside project " + projectId + "'s bounds [" + lowerBound
                        + ".." + upperBound + "]");
    }

    /**
     * Returns the machine-readable error code carried by this exception.
     *
     * @return {@link #CODE_REQUIRED} or {@link #CODE_OUT_OF_BOUNDS}
     */
    String code() {
        return code;
    }
}

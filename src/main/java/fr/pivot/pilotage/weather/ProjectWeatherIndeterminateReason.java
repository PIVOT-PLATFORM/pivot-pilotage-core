package fr.pivot.pilotage.weather;

/**
 * Explains why {@link ProjectWeatherService} returned {@link ProjectWeatherStatus#INDETERMINATE}
 * (US23.2.4 error case) — the API/entity never falls back to a misleading default weather when
 * data is insufficient; it always says exactly what is missing.
 */
public enum ProjectWeatherIndeterminateReason {

    /** The project has no {@code statusDate} (EN22.1a) — no reference date to evaluate against. */
    MISSING_STATUS_DATE,

    /**
     * No usable temporal window could be derived from the project's tasks (no dates at all, or an
     * inconsistent window where the earliest start is after the latest finish).
     */
    MISSING_WINDOW,

    /** No leaf task of the project carries a {@code task_progress} record. */
    MISSING_PROGRESS
}

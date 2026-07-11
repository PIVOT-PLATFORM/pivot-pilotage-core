package fr.pivot.pilotage.schedule.projection;

import java.util.Locale;

/**
 * View altitude (EN22.1c, frozen contract §c) — the render cursor that selects which projection of
 * the single temporal graph is returned. It is <strong>not</strong> a graph value: it never mutates
 * the graph, it only chooses which bounds are read.
 *
 * <ul>
 *   <li>{@link #MACRO} — roadmap view: reads the fuzzy period bounds ({@code fuzzy_period_*}) and
 *       projects shared milestones plus summary rollups.</li>
 *   <li>{@link #DETAIL} — Gantt view: reads the precise dates ({@code start_date}/{@code
 *       finish_date}), dependencies and engine-derived scheduling.</li>
 * </ul>
 *
 * <p>The default altitude is supplied by {@code resolveProfile(tenant).altitude} (EN18.10, seam
 * {@code DefaultAltitudeProvider}); a request may override it without touching the graph.
 */
public enum Altitude {

    /** Macro (roadmap) altitude — fuzzy periods, shared milestones, summary rollups. */
    MACRO,

    /** Detail (Gantt) altitude — precise dates, dependencies, critical path. */
    DETAIL;

    /**
     * Parses a request altitude token case-insensitively.
     *
     * @param raw the token (e.g. {@code "macro"}, {@code "DETAIL"})
     * @return the matching {@link Altitude}
     * @throws UnknownAltitudeException if the token is {@code null} or not a known altitude — mapped
     *                                  to HTTP 422 at the (deferred) controller layer; no partial
     *                                  projection is ever returned
     */
    public static Altitude parse(final String raw) {
        if (raw == null) {
            throw new UnknownAltitudeException(null);
        }
        try {
            return Altitude.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new UnknownAltitudeException(raw);
        }
    }
}

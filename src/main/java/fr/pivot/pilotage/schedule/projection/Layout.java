package fr.pivot.pilotage.schedule.projection;

/**
 * Rendering layout of a projection (EN22.1c, frozen contract §c) — a hint carried alongside the
 * {@link Altitude} that shapes how the derived nodes are laid out for a given consumer view.
 *
 * <p>The layout does not change which graph values are authoritative (that is the altitude's job);
 * it selects the consumer-facing arrangement:
 *
 * <ul>
 *   <li>{@link #TIMELINE} — macro roadmap on a fuzzy time axis (US22.3.2).</li>
 *   <li>{@link #BUCKETS} — Now/Next/Later, grouped by {@code horizon}, no time axis (US22.3.3).</li>
 *   <li>{@link #GANTT} — detail WBS tree with dependencies (US22.4.1a / US22.4.3).</li>
 * </ul>
 */
public enum Layout {

    /** Macro roadmap on a fuzzy time axis (timeline). */
    TIMELINE,

    /** Now/Next/Later buckets grouped by horizon, no temporal axis. */
    BUCKETS,

    /** Detail Gantt (WBS tree + dependency edges). */
    GANTT
}

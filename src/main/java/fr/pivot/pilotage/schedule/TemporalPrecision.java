package fr.pivot.pilotage.schedule;

/**
 * Effective temporal precision (altitude) of a graph node (EN22.1a, frozen contract §a).
 *
 * <p>This is the <em>effective</em> altitude persisted per {@code task} — the grain at which the
 * node's time is expressed. At {@link #DAY} the precise {@code start_date}/{@code finish_date}
 * are authoritative; at {@link #WEEK} and coarser, the fuzzy period bounds
 * ({@code fuzzy_period_start}/{@code fuzzy_period_end}) are authoritative. Enforcing which bound
 * is authoritative is a service/engine rule (EN22.1b), not carried by the schema.
 *
 * <p>This is distinct from the <em>default</em> altitude / view cursor resolved via the tenant
 * profile (EN18.10): the profile is policy, the node value is data.
 */
public enum TemporalPrecision {

    /** Half-year grain (coarsest roadmap altitude). */
    SEMESTER,

    /** Quarter grain. */
    QUARTER,

    /** Month grain. */
    MONTH,

    /** Week grain. */
    WEEK,

    /** Day grain — precise Gantt dates are authoritative. */
    DAY
}

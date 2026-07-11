package fr.pivot.pilotage.profile;

/**
 * Default rigor level of an organization profile (EN18.10, frozen contract §c).
 *
 * <p>One of the four deterministic, non-null attributes of a {@link DefaultOrganizationProfile}.
 * It is <strong>policy</strong>, not an engine input: it never influences scheduling or the
 * projection of the temporal graph — it carries a default methodological-rigor posture (how much
 * ceremony/governance the organization applies by default) that downstream consumers read. The
 * default backing (EN18.10) supplies a fixed value; E40 will later substitute an adaptive
 * resolution behind the <em>same</em> contract.
 *
 * <p>Ordered from the lightest to the strictest; the versioned default is {@link #STANDARD}.
 */
public enum RigorLevel {

    /** Lightest rigor — minimal governance ceremony. */
    LIGHT,

    /** Standard rigor — balanced governance (versioned default). */
    STANDARD,

    /** Strictest rigor — maximal governance ceremony. */
    STRICT
}

package fr.pivot.pilotage.profile;

/**
 * Default sovereignty class of an organization profile (EN18.10, frozen contract §c).
 *
 * <p>One of the four deterministic, non-null attributes of a {@link DefaultOrganizationProfile}.
 * It is <strong>policy</strong>, not an engine input: it never influences scheduling or the
 * projection of the temporal graph — it carries a default data-sovereignty posture that downstream
 * consumers (E03 module activation, future hosting/routing policy) read. The default backing
 * (EN18.10) supplies a fixed value; E40 will later substitute an adaptive resolution behind the
 * <em>same</em> contract.
 *
 * <p>Ordered from the most neutral to the most constrained; the versioned default is the most
 * neutral ({@link #NEUTRAL}) per the fiche's proposed defaults.
 */
public enum SovereigntyClass {

    /** Most neutral posture — no specific data-sovereignty constraint (versioned default). */
    NEUTRAL,

    /** Restricted posture — some sovereignty constraints apply. */
    RESTRICTED,

    /** Sovereign posture — strict data-sovereignty constraints apply. */
    SOVEREIGN
}

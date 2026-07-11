package fr.pivot.pilotage.profile;

/**
 * Default sovereignty class of an organization profile (EN18.10, frozen contract §c), aligned on
 * the platform-wide zone vocabulary of {@code ADR-015-zones-souverainete-segmentation.md}.
 *
 * <p>One of the four deterministic, non-null attributes of a {@link DefaultOrganizationProfile}.
 * It is <strong>policy</strong>, not an engine input: it never influences scheduling or the
 * projection of the temporal graph — it carries a default data-sovereignty posture that downstream
 * consumers (E03 module activation, future hosting/routing policy) read. The default backing
 * (EN18.10) supplies a fixed value; E40 will later substitute an adaptive resolution behind the
 * <em>same</em> contract.
 *
 * <p><strong>Vocabulary retrofit (EN18.10 écart #2).</strong> This enum used to be
 * {@code NEUTRAL}/{@code RESTRICTED}/{@code SOVEREIGN}, inconsistent with ADR-015's three zones
 * (A — souveraine, B — contrôlée, C — DMZ externe). Renamed here to that vocabulary. Mapping
 * retained (documented in full on the {@code pilotage.organization_profile} migration comment):
 * <ul>
 *   <li>{@code SOVEREIGN} → {@link #ZONE_A_SOUVERAINE} — same lexical root ("souverain"/
 *       "sovereign"), strict data-sovereignty constraints, self-host/air-gap.</li>
 *   <li>{@code NEUTRAL} → {@link #ZONE_B_CONTROLEE} — <strong>also the versioned default</strong>:
 *       "the most neutral class" is standard SaaS multi-tenant operation (EU tenant/VPC,
 *       filtered egress), <em>not</em> zone A (disproportionately restrictive as a universal
 *       default) nor zone C (reserved for explicit third-party/DMZ exposure, inappropriate as a
 *       baseline default).</li>
 *   <li>{@code RESTRICTED} → {@link #ZONE_C_DMZ_EXTERNE} — by elimination, an explicit
 *       external-exposure posture.</li>
 * </ul>
 */
public enum SovereigntyClass {

    /**
     * Zone A — souveraine (ADR-015): self-hosted, air-gap possible, no outbound flow to B/C,
     * critical data cantoned. Strictest posture (formerly {@code SOVEREIGN}).
     */
    ZONE_A_SOUVERAINE,

    /**
     * Zone B — contrôlée (ADR-015): perimeter-controlled modules (EU tenant, VPC), filtered
     * outbound flows, contractual EU residency. <strong>Versioned default</strong> (formerly
     * {@code NEUTRAL}) — see class Javadoc for why this is the safe default, not zone A or C.
     */
    ZONE_B_CONTROLEE,

    /**
     * Zone C — DMZ externe (ADR-015): third-party API connections via the Egress Gateway
     * (ADR-012), never sensitive data. Formerly {@code RESTRICTED}.
     */
    ZONE_C_DMZ_EXTERNE
}

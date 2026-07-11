package fr.pivot.pilotage.consolidation;

import java.util.Optional;

/**
 * Cross-module extension point (SPI) for the "consolidation by Application" contract (EN18.9,
 * ADR-006/ADR-008). A module <em>other</em> than pilotage (budget E26, risks E21, decisions/ADR
 * E24…) contributes its own per-application aggregate here so it surfaces in the
 * {@link ApplicationConsolidation}, <strong>without pilotage ever traversing an inter-module
 * foreign key</strong>: the pilotage schema owns none of those tables, and reaching into them by FK
 * is exactly the coupling ADR-006 forbids.
 *
 * <p><strong>Contract.</strong> An implementation returns its aggregate for the given
 * {@code (tenantId, applicationId)} or {@link Optional#empty()} when it has nothing to contribute
 * (or the application is outside its scope). Implementations must be tenant-scoped: they must never
 * return data belonging to another tenant.
 *
 * <p><strong>Wiring is a documented gap.</strong> The real transport is the PIVOT inter-module event
 * bus, whose typed envelopes and request/reply plumbing live in {@code pivot-core} and are exported
 * via {@code fr.pivot:pivot-core-starter}. That starter is not yet a consumable Maven artifact
 * (CLAUDE.md §gap, TODO-SETUP §5), so this repo ships only the <em>seam</em>: the interface plus a
 * no-op default ({@link NoOpApplicationDataContributor}). A future bus-backed implementation (in a
 * post-starter US) plugs in here as an additional Spring bean, needing no change to
 * {@link ApplicationConsolidationService}. This repo deliberately does <strong>not</strong> invent a
 * fake bus client.
 */
public interface ApplicationDataContributor {

    /**
     * Contributes this module's per-application aggregate.
     *
     * @param tenantId      the requesting tenant's {@code public.tenants.id} (isolation boundary —
     *                      an implementation must never cross it)
     * @param applicationId the application to aggregate for
     * @return the module's aggregate, or {@link Optional#empty()} if it has nothing to contribute
     */
    Optional<ApplicationAggregateContribution> contribute(long tenantId, long applicationId);
}

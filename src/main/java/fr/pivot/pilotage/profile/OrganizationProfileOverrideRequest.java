package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Request body of {@code PUT /api/pilotage/organization-profile/{tenantId}} (EN18.10 écart #3) —
 * the payload creating or replacing a tenant's {@link OrganizationProfile} override.
 *
 * <p>{@code teamId} is mandatory (the {@code pilotage.organization_profile.team_id} retrofit
 * column is {@code NOT NULL}) — the team on behalf of which the override is written, validated
 * against {@code public.teams} by {@link OrganizationProfileOverrideService} before any write.
 * This DTO is the only way a client supplies profile-override data — the JPA entity
 * ({@link OrganizationProfile}) is never bound directly to a request (CLAUDE.md §Standards).
 *
 * @param teamId           the team the override is attributed to, {@code public.teams.id}
 * @param altitude         the overridden view altitude
 * @param sovereigntyClass the overridden sovereignty class (ADR-015 zone)
 * @param rigorLevel       the overridden rigor level
 * @param defaultModules   the overridden set of default module ids (never {@code null}; a
 *                         {@code null} JSON value is normalized to an empty set)
 */
public record OrganizationProfileOverrideRequest(
        @NotNull Long teamId,
        @NotNull Altitude altitude,
        @NotNull SovereigntyClass sovereigntyClass,
        @NotNull RigorLevel rigorLevel,
        @NotEmpty Set<String> defaultModules) {

    /**
     * Canonical constructor defensively copying {@code defaultModules} into an unmodifiable set,
     * normalizing a {@code null} value to an empty set so the compact constructor never throws
     * before bean validation reports the missing field as a 400 (not a 500 NPE).
     */
    public OrganizationProfileOverrideRequest {
        defaultModules = defaultModules == null ? Set.of() : Set.copyOf(defaultModules);
    }
}

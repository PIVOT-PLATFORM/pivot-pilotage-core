package fr.pivot.pilotage.profile;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the {@code pilotage.organization_profile} override write path
 * (EN18.10 écart #3) — the first controller in this module (CLAUDE.md §gap, {@code
 * pivot-core-starter}/{@code TenantContext} not yet published, so {@code tenantId} is taken from
 * the path, never a body/param/header per the read-side contract, matching every other
 * cross-tenant check in this repo).
 *
 * <p>No business logic here (CLAUDE.md §Standards "Pas de logique dans les contrôleurs") — the
 * role gate is delegated to {@link OrganizationProfileOverridePolicy} and the write itself to
 * {@link OrganizationProfileOverrideService}; both are exception-mapped by
 * {@link OrganizationProfileExceptionHandler}.
 */
@RestController
@RequestMapping("/organization-profile")
public class OrganizationProfileController {

    private final OrganizationProfileOverrideService overrideService;
    private final OrganizationProfileOverridePolicy overridePolicy;

    /**
     * Constructs the controller.
     *
     * @param overrideService the write-path service
     * @param overridePolicy  the role-gate extension point (EN18.10 écart #3)
     */
    public OrganizationProfileController(final OrganizationProfileOverrideService overrideService,
            final OrganizationProfileOverridePolicy overridePolicy) {
        this.overrideService = overrideService;
        this.overridePolicy = overridePolicy;
    }

    /**
     * Creates or replaces the tenant's organization-profile override.
     *
     * <p>{@code teamId} is not a path segment — it is part of the request body, alongside the
     * overridden attributes, because {@code pilotage.organization_profile} stays resolved by
     * tenant alone (frozen {@code resolveProfile(tenant)} contract); {@code teamId} is required
     * attribution metadata, not a lookup key (see {@link OrganizationProfile} Javadoc).
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param request  the override payload
     * @return {@code 204 No Content} on success; {@code 403} if the caller is not authorized;
     *         {@code 404} if {@code tenantId} or the request's {@code teamId} does not resolve
     */
    @PutMapping("/{tenantId}")
    public ResponseEntity<Void> putOverride(@PathVariable final long tenantId,
            @Valid @RequestBody final OrganizationProfileOverrideRequest request) {
        if (!overridePolicy.isAuthorized()) {
            throw new OrganizationProfileOverrideForbiddenException();
        }
        overrideService.upsertOverride(tenantId, request);
        return ResponseEntity.noContent().build();
    }
}

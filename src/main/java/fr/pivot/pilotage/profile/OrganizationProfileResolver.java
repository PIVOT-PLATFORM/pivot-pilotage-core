package fr.pivot.pilotage.profile;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped resolver for the {@link DefaultOrganizationProfile} (EN18.10, frozen contract §c) —
 * the stable {@code resolveProfile(tenant)} read contract E22 (view cursor) and E03 (module
 * activation) consume, <strong>substitutable</strong> by E40 without touching the callers.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>Unknown tenant (no {@code public.tenants} row) → {@link TenantNotFoundException} (HTTP 404
 *       equivalent) — never a fabricated phantom profile.</li>
 *   <li>Override row present in {@code pilotage.organization_profile} for the tenant → that
 *       override (one row per tenant, {@code UNIQUE}).</li>
 *   <li>No override → the versioned default ({@link DefaultProfileProperties}, resolved on the fly,
 *       never persisted).</li>
 *   <li>Cross-tenant isolation: only the requested tenant's override is ever read.</li>
 * </ul>
 *
 * <p><strong>Policy, not engine.</strong> This resolver produces a policy value; the altitude it
 * carries only parametrizes the projection (EN22.1c), it never feeds the scheduling engine. See
 * {@code fr.pivot.pilotage.schedule.projection.ProfileBackedAltitudeProvider} for the seam wiring.
 *
 * <p><strong>REST deferred.</strong> Per CLAUDE.md §gap and TODO-SETUP §5, {@code
 * pivot-core-starter} (TenantContext) is not published, so {@code tenantId} is an explicit
 * argument, never taken from a body/param/header; the future controller maps
 * {@link TenantNotFoundException} to 404.
 */
@Service
public class OrganizationProfileResolver {

    private final OrganizationProfileRepository profileRepository;
    private final DefaultProfileProperties defaults;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the resolver.
     *
     * @param profileRepository repository for tenant profile overrides
     * @param defaults          the versioned default profile policy
     * @param jdbcTemplate      JDBC template used only to probe {@code public.tenants} existence
     *                          (that table is owned by {@code pivot-core}, not mapped here)
     * @param objectMapper      Jackson mapper for the JSONB {@code default_modules} array
     */
    public OrganizationProfileResolver(final OrganizationProfileRepository profileRepository,
            final DefaultProfileProperties defaults, final JdbcTemplate jdbcTemplate,
            final ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.defaults = defaults;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the default organization profile for a tenant.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the resolved {@link DefaultOrganizationProfile} (override if present, else the
     *         versioned default) — always with four non-null attributes
     * @throws TenantNotFoundException if no tenant exists for {@code tenantId} (HTTP 404 equivalent)
     */
    @Transactional(readOnly = true)
    public DefaultOrganizationProfile resolveProfile(final long tenantId) {
        if (!tenantExists(tenantId)) {
            throw new TenantNotFoundException(tenantId);
        }
        return profileRepository.findByTenantId(tenantId)
                .map(this::toProfile)
                .orElseGet(defaults::toProfile);
    }

    /**
     * Probes whether a tenant exists in {@code public.tenants}. Deliberately raw SQL against a
     * table this module never maps (owned by {@code pivot-core}).
     *
     * @param tenantId the tenant id to check
     * @return {@code true} if a matching row exists
     */
    private boolean tenantExists(final long tenantId) {
        final Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.tenants WHERE id = ?", Integer.class, tenantId);
        return count != null && count > 0;
    }

    /**
     * Maps a persisted override entity to the immutable profile record, parsing the JSONB module
     * array.
     *
     * @param entity the override entity
     * @return the immutable profile
     */
    private DefaultOrganizationProfile toProfile(final OrganizationProfile entity) {
        return new DefaultOrganizationProfile(entity.getAltitude(), entity.getSovereigntyClass(),
                entity.getRigorLevel(), parseModules(entity.getDefaultModules()));
    }

    /**
     * Parses the JSONB {@code default_modules} array string into an ordered set. A blank/empty
     * value yields an empty set; malformed JSON is a data-integrity error surfaced as a runtime
     * exception (never silently swallowed).
     *
     * @param json the JSON array string (may be {@code null}/blank)
     * @return the module ids as an ordered set
     */
    private Set<String> parseModules(final String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            final Set<String> parsed = objectMapper.readValue(json, new TypeReference<LinkedHashSet<String>>() { });
            return Optional.ofNullable(parsed).map(Set::copyOf).orElseGet(Set::of);
        } catch (final JacksonException e) {
            throw new MalformedProfileModulesException(json, e);
        }
    }

    /**
     * Thrown when a persisted {@code default_modules} JSONB value is not a valid JSON string array.
     */
    static final class MalformedProfileModulesException extends DataAccessException {

        private static final long serialVersionUID = 1L;

        MalformedProfileModulesException(final String json, final Throwable cause) {
            super("Malformed default_modules JSON: " + json, cause);
        }
    }
}

package fr.pivot.pilotage.profile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write-path service for the {@code pilotage.organization_profile} override (EN18.10 écart #3) —
 * backs {@link OrganizationProfileController}'s {@code PUT
 * /api/pilotage/organization-profile/{tenantId}} endpoint.
 *
 * <p>Validates, in order: the tenant exists ({@link TenantNotFoundException}, 404 equivalent) and
 * the request's {@code teamId} exists <em>and</em> belongs to that tenant
 * ({@link TeamNotFoundException}, 404 equivalent — same non-disclosure posture as every other
 * cross-tenant check in this module, CLAUDE.md §Isolation tenant). Both checks are deliberately
 * raw JDBC against {@code public.*}, mirroring
 * {@code DefaultOrganizationProfileResolver.tenantExists} — this module never maps
 * {@code public.tenants}/{@code public.teams} as JPA entities (ADR-006/ADR-022).
 *
 * <p>Upserts the single per-tenant override row: creates it if absent, otherwise replaces every
 * overridden field (full replace semantics, matching {@code PUT}).
 */
@Service
public class OrganizationProfileOverrideService {

    private final OrganizationProfileRepository profileRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the override service.
     *
     * @param profileRepository repository for tenant profile overrides
     * @param jdbcTemplate      JDBC template used only to probe {@code public.tenants}/
     *                          {@code public.teams} (owned by {@code pivot-core}, not mapped here)
     * @param objectMapper      Jackson mapper for the JSONB {@code default_modules} array
     */
    public OrganizationProfileOverrideService(final OrganizationProfileRepository profileRepository,
            final JdbcTemplate jdbcTemplate, final ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates or replaces the tenant's organization-profile override.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param request  the override payload (already role-checked by the controller)
     * @throws TenantNotFoundException if no tenant exists for {@code tenantId}
     * @throws TeamNotFoundException   if {@code request.teamId()} does not exist, or belongs to a
     *                                 different tenant
     */
    @Transactional
    public void upsertOverride(final long tenantId, final OrganizationProfileOverrideRequest request) {
        if (!tenantExists(tenantId)) {
            throw new TenantNotFoundException(tenantId);
        }
        if (!teamBelongsToTenant(request.teamId(), tenantId)) {
            throw new TeamNotFoundException(request.teamId(), tenantId);
        }

        final String modulesJson = writeModules(request.defaultModules());
        final OrganizationProfile entity = profileRepository.findByTenantId(tenantId)
                .orElseGet(() -> new OrganizationProfile(tenantId, request.teamId(), request.altitude(),
                        request.sovereigntyClass(), request.rigorLevel(), modulesJson));

        entity.setTeamId(request.teamId());
        entity.setAltitude(request.altitude());
        entity.setSovereigntyClass(request.sovereigntyClass());
        entity.setRigorLevel(request.rigorLevel());
        entity.setDefaultModules(modulesJson);
        profileRepository.save(entity);
    }

    /**
     * Probes whether a tenant exists in {@code public.tenants}.
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
     * Probes whether a team exists in {@code public.teams} <em>and</em> belongs to the given
     * tenant — the applicative tenant/team consistency check documented on the
     * {@code pilotage.organization_profile} migration comment (no composite SQL FK is possible:
     * {@code public.teams} exposes no {@code UNIQUE (id, tenant_id)} key), same posture as
     * {@code pivot-agilite-core}'s {@code PlatformTeam}/{@code RetroSessionService}.
     *
     * @param teamId   the team id to check
     * @param tenantId the tenant the team must belong to
     * @return {@code true} if the team exists and belongs to that tenant
     */
    private boolean teamBelongsToTenant(final long teamId, final long tenantId) {
        final Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.teams WHERE id = ? AND tenant_id = ?",
                Integer.class, teamId, tenantId);
        return count != null && count > 0;
    }

    /**
     * Serializes the default module ids into the JSONB array string persisted by
     * {@link OrganizationProfile#getDefaultModules()}.
     *
     * @param modules the module ids (never {@code null}, enforced by
     *                {@link OrganizationProfileOverrideRequest}'s compact constructor)
     * @return the JSON array string
     */
    private String writeModules(final Set<String> modules) {
        try {
            return objectMapper.writeValueAsString(List.copyOf(modules));
        } catch (final JacksonException e) {
            throw new SerializationFailedException(e);
        }
    }

    /** Thrown if serializing {@code defaultModules} to JSON unexpectedly fails. */
    static final class SerializationFailedException extends DataAccessException {

        private static final long serialVersionUID = 1L;

        SerializationFailedException(final Throwable cause) {
            super("Failed to serialize defaultModules to JSON", cause);
        }
    }
}

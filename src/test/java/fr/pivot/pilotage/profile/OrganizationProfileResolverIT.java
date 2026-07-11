package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link OrganizationProfileResolver} (EN18.10) against a real PostgreSQL 18
 * (Testcontainers) with this module's Flyway applied. One test per frozen acceptance criterion:
 * versioned default (no override), DB override read, one-profile-per-tenant uniqueness, unknown
 * tenant → {@link TenantNotFoundException} (404 equivalent), four non-null attributes, and
 * multi-tenant isolation (never another tenant's override). The {@code public.tenants} table
 * (owned by {@code pivot-core}) is seeded before Spring/Flyway via {@link PlatformSchemaTestSupport}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrganizationProfileResolverIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /**
     * Registers the container datasource and seeds {@code public} before Spring/Flyway.
     *
     * @param registry the dynamic property registry
     * @throws Exception if seeding the public schema fails
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        PlatformSchemaTestSupport.createPublicSchema(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Autowired
    private OrganizationProfileResolver resolver;

    @Autowired
    private OrganizationProfileRepository profileRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long teamId;

    /** Seeds a fresh tenant and team in {@code public.tenants}/{@code public.teams} before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
        teamId = seedTeam(tenantId);
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTeam(final long owner) throws Exception {
        return PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), owner);
    }

    // -------- AC: no override → versioned default (macro / zone B / standard / roadmap) ----------

    @Test
    void noOverride_returnsVersionedDefault() {
        final DefaultOrganizationProfile profile = resolver.resolveProfile(tenantId);

        assertThat(profile.altitude()).isEqualTo(Altitude.MACRO);
        assertThat(profile.sovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_B_CONTROLEE);
        assertThat(profile.rigorLevel()).isEqualTo(RigorLevel.STANDARD);
        assertThat(profile.defaultModules()).containsExactly("roadmap");
        // No phantom row was written when falling back to the versioned default.
        assertThat(profileRepository.findByTenantId(tenantId)).isEmpty();
    }

    // -------- AC: the four resolved attributes are always non-null and deterministic -----------

    @Test
    void resolvedProfile_hasFourNonNullAttributes() {
        final DefaultOrganizationProfile profile = resolver.resolveProfile(tenantId);
        assertThat(profile.altitude()).isNotNull();
        assertThat(profile.sovereigntyClass()).isNotNull();
        assertThat(profile.rigorLevel()).isNotNull();
        assertThat(profile.defaultModules()).isNotNull();
        // Deterministic: a second call yields an equal profile.
        assertThat(resolver.resolveProfile(tenantId)).isEqualTo(profile);
    }

    // -------- AC: an override row present in DB is read and wins over the versioned default -----

    @Test
    void override_isReadFromDatabase() {
        profileRepository.save(new OrganizationProfile(tenantId, teamId, Altitude.DETAIL,
                SovereigntyClass.ZONE_A_SOUVERAINE, RigorLevel.STRICT, "[\"roadmap\",\"gantt\"]"));

        final DefaultOrganizationProfile profile = resolver.resolveProfile(tenantId);
        assertThat(profile.altitude()).isEqualTo(Altitude.DETAIL);
        assertThat(profile.sovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_A_SOUVERAINE);
        assertThat(profile.rigorLevel()).isEqualTo(RigorLevel.STRICT);
        assertThat(profile.defaultModules()).containsExactlyInAnyOrder("roadmap", "gantt");
    }

    // -------- AC: exactly one profile per tenant (UNIQUE tenant_id) -----------------------------

    @Test
    void oneProfilePerTenant_secondInsertViolatesUnique() {
        profileRepository.saveAndFlush(new OrganizationProfile(tenantId, teamId, Altitude.MACRO,
                SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, "[\"roadmap\"]"));

        assertThatThrownBy(() -> profileRepository.saveAndFlush(new OrganizationProfile(tenantId, teamId,
                Altitude.DETAIL, SovereigntyClass.ZONE_C_DMZ_EXTERNE, RigorLevel.LIGHT, "[\"gantt\"]")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------- AC (error case): unknown tenant → TenantNotFoundException (404 equivalent) --------

    @Test
    void unknownTenant_throwsTenantNotFound() {
        final long unknownTenantId = 987_654_321L;
        assertThatExceptionOfType(TenantNotFoundException.class)
                .isThrownBy(() -> resolver.resolveProfile(unknownTenantId));
        // No phantom profile fabricated.
        assertThat(profileRepository.findByTenantId(unknownTenantId)).isEmpty();
    }

    // -------- AC (security): isolation — never resolve another tenant's override ----------------

    @Test
    void isolation_neverReadsAnotherTenantsOverride() throws Exception {
        final long tenantT2 = seedTenant();
        final long teamT2 = seedTeam(tenantT2);
        // Only T2 has an override.
        profileRepository.save(new OrganizationProfile(tenantT2, teamT2, Altitude.DETAIL,
                SovereigntyClass.ZONE_A_SOUVERAINE, RigorLevel.STRICT, "[\"gantt\"]"));

        // T1 (no override) resolves the versioned default, never T2's override.
        final DefaultOrganizationProfile t1 = resolver.resolveProfile(tenantId);
        assertThat(t1.altitude()).isEqualTo(Altitude.MACRO);
        assertThat(t1.sovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_B_CONTROLEE);
        assertThat(t1.defaultModules()).containsExactly("roadmap");

        // T2 resolves its own override.
        final DefaultOrganizationProfile t2 = resolver.resolveProfile(tenantT2);
        assertThat(t2.altitude()).isEqualTo(Altitude.DETAIL);
        assertThat(t2.defaultModules()).containsExactly("gantt");
    }

    // -------- Data integrity: malformed JSONB modules surfaces, never silently swallowed --------

    @Test
    void malformedModulesJson_surfacesAsError() {
        jdbcTemplate.update("INSERT INTO pilotage.organization_profile "
                + "(tenant_id, team_id, altitude, sovereignty_class, rigor_level, default_modules) "
                + "VALUES (?, ?, 'MACRO', 'ZONE_B_CONTROLEE', 'STANDARD', ?::jsonb)",
                tenantId, teamId, "{\"not\":\"an-array\"}");

        assertThatThrownBy(() -> resolver.resolveProfile(tenantId))
                .isInstanceOf(RuntimeException.class);
    }
}

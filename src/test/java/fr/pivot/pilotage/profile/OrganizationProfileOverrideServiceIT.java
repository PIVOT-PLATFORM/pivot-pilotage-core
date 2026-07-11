package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link OrganizationProfileOverrideService} (EN18.10 écart #3) against a
 * real PostgreSQL 18 (Testcontainers). Covers: creating the override row when none exists,
 * full-replace semantics on a second call, an unknown tenant, a {@code teamId} that exists but
 * belongs to a different tenant, and a {@code teamId} that does not exist at all — the last two
 * both surface as {@link TeamNotFoundException} (same non-disclosure posture, CLAUDE.md §Isolation
 * tenant). The {@code public.tenants}/{@code public.teams} tables (owned by {@code pivot-core})
 * are seeded before Flyway via {@link PlatformSchemaTestSupport}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrganizationProfileOverrideServiceIT {

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
    private OrganizationProfileOverrideService overrideService;

    @Autowired
    private OrganizationProfileRepository profileRepository;

    private long tenantId;
    private long teamId;

    /** Seeds a fresh tenant and team before each test. */
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

    private OrganizationProfileOverrideRequest request(final long team, final Altitude altitude,
            final SovereigntyClass sovereigntyClass, final RigorLevel rigorLevel, final Set<String> modules) {
        return new OrganizationProfileOverrideRequest(team, altitude, sovereigntyClass, rigorLevel, modules);
    }

    // -------- AC: creates a new override row when none exists ------------------------------------

    @Test
    void upsertOverride_createsRowWhenNoneExists() {
        assertThat(profileRepository.findByTenantId(tenantId)).isEmpty();

        overrideService.upsertOverride(tenantId, request(teamId, Altitude.DETAIL,
                SovereigntyClass.ZONE_A_SOUVERAINE, RigorLevel.STRICT, Set.of("roadmap", "gantt")));

        final OrganizationProfile saved = profileRepository.findByTenantId(tenantId).orElseThrow();
        assertThat(saved.getTeamId()).isEqualTo(teamId);
        assertThat(saved.getAltitude()).isEqualTo(Altitude.DETAIL);
        assertThat(saved.getSovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_A_SOUVERAINE);
        assertThat(saved.getRigorLevel()).isEqualTo(RigorLevel.STRICT);
    }

    // -------- AC: a second call replaces every overridden field (full-replace semantics) --------

    @Test
    void upsertOverride_secondCallReplacesExistingRow() throws Exception {
        overrideService.upsertOverride(tenantId, request(teamId, Altitude.MACRO,
                SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, Set.of("roadmap")));
        final long teamB = seedTeam(tenantId);

        overrideService.upsertOverride(tenantId, request(teamB, Altitude.DETAIL,
                SovereigntyClass.ZONE_A_SOUVERAINE, RigorLevel.STRICT, Set.of("roadmap", "gantt")));

        // Still exactly one row for the tenant (UNIQUE tenant_id) — replaced, not duplicated.
        assertThat(profileRepository.findAll()).hasSize(1);
        final OrganizationProfile reloaded = profileRepository.findByTenantId(tenantId).orElseThrow();
        assertThat(reloaded.getTeamId()).isEqualTo(teamB);
        assertThat(reloaded.getAltitude()).isEqualTo(Altitude.DETAIL);
        assertThat(reloaded.getSovereigntyClass()).isEqualTo(SovereigntyClass.ZONE_A_SOUVERAINE);
        assertThat(reloaded.getRigorLevel()).isEqualTo(RigorLevel.STRICT);
    }

    // -------- AC (error case): unknown tenant → TenantNotFoundException --------------------------

    @Test
    void upsertOverride_unknownTenant_throwsTenantNotFound() {
        final long unknownTenantId = 987_654_321L;

        assertThatExceptionOfType(TenantNotFoundException.class).isThrownBy(() ->
                overrideService.upsertOverride(unknownTenantId, request(teamId, Altitude.MACRO,
                        SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, Set.of("roadmap"))));
        assertThat(profileRepository.findAll()).isEmpty();
    }

    // -------- AC (security): a team belonging to a different tenant → TeamNotFoundException ------

    @Test
    void upsertOverride_teamBelongsToDifferentTenant_throwsTeamNotFound() throws Exception {
        final long tenantT2 = seedTenant();
        final long teamT2 = seedTeam(tenantT2);

        assertThatExceptionOfType(TeamNotFoundException.class).isThrownBy(() ->
                overrideService.upsertOverride(tenantId, request(teamT2, Altitude.MACRO,
                        SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, Set.of("roadmap"))));
        assertThat(profileRepository.findByTenantId(tenantId)).isEmpty();
    }

    // -------- AC (error case): a team that does not exist at all → TeamNotFoundException ---------

    @Test
    void upsertOverride_unknownTeam_throwsTeamNotFound() {
        final long unknownTeamId = 987_654_321L;

        assertThatExceptionOfType(TeamNotFoundException.class).isThrownBy(() ->
                overrideService.upsertOverride(tenantId, request(unknownTeamId, Altitude.MACRO,
                        SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD, Set.of("roadmap"))));
        assertThat(profileRepository.findByTenantId(tenantId)).isEmpty();
    }
}

package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link RoadmapShareService} (US22.3.5) against a real PostgreSQL 18
 * (Testcontainers), with this module's Flyway migration applied — covers the raw-token
 * non-disclosure guarantee, the full create/view/revoke lifecycle, per-project data isolation on
 * the public view, expiry enforcement, and multi-tenant/multi-team management isolation.
 * {@code public.tenants}/{@code public.teams} are seeded before Flyway via
 * {@link PlatformSchemaTestSupport}. Mirrors {@code fr.pivot.pilotage.roadmap.RoadmapServiceIT}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RoadmapShareServiceIT {

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

    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RoadmapShareLinkRepository shareLinkRepository;
    @Autowired private RoadmapService roadmapService;
    @Autowired private RoadmapShareService shareService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long teamId;
    private long projectId;

    /** Seeds a fresh tenant/team/application/project before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
        teamId = seedTeam(tenantId);
        projectId = newProject(tenantId, teamId, "P").getId();
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTeam(final long owner) throws Exception {
        return PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), owner);
    }

    private Project newProject(final long owner, final long team, final String name) {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(owner, team, "App-" + name, now));
        return projectRepository.save(new Project(app, owner, team, name, now));
    }

    // -------- Security AC: the raw token is never persisted, only its hash ------------------------

    @Test
    void createShareLink_rawTokenNeverPersisted_onlyItsHashIs() {
        final CreateShareLinkResponse created = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));

        final RoadmapShareLink persisted = shareLinkRepository.findById(created.id()).orElseThrow();
        assertThat(persisted.getTokenHash()).isNotEqualTo(created.token());
        assertThat(persisted.getTokenHash()).hasSize(64);

        // Direct row check: the raw token string must not appear anywhere in the persisted row.
        final String rawColumnValue = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM pilotage.roadmap_share_link WHERE id = ?", String.class, created.id());
        assertThat(rawColumnValue).isNotEqualTo(created.token());
    }

    // -------- AC: a read-only share link lets a recipient view the roadmap without editing ------

    @Test
    void fullLifecycle_createThenView_returnsProjectRoadmap() {
        roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Theme A"));

        final CreateShareLinkResponse created = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));

        final RoadmapShareViewResponse view = shareService.viewSharedRoadmap(created.token());

        assertThat(view.projectName()).isEqualTo("P");
        assertThat(view.lanes()).extracting(LaneResponse::name).containsExactly("Theme A");
    }

    // -------- Error case + Security AC: a revoked link denies access, no partial display ----------

    @Test
    void revokedLink_view_deniedWithNoPartialData() {
        final CreateShareLinkResponse created = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));
        assertThat(shareService.viewSharedRoadmap(created.token())).isNotNull();

        shareService.revokeShareLink(tenantId, teamId, projectId, created.id());

        assertThatExceptionOfType(ShareLinkAccessDeniedException.class)
                .isThrownBy(() -> shareService.viewSharedRoadmap(created.token()));
    }

    @Test
    void revoke_isIdempotent_secondCallStillDeniesAccess() {
        final CreateShareLinkResponse created = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));

        shareService.revokeShareLink(tenantId, teamId, projectId, created.id());
        shareService.revokeShareLink(tenantId, teamId, projectId, created.id());

        assertThatExceptionOfType(ShareLinkAccessDeniedException.class)
                .isThrownBy(() -> shareService.viewSharedRoadmap(created.token()));
    }

    // -------- Error case: an expired link denies access -------------------------------------------

    @Test
    void expiredLink_view_deniedAccess() {
        // Bypasses createShareLink's future-only validation on purpose — persists an
        // already-expired row directly (with a real, known raw token's hash) to test
        // viewSharedRoadmap's expiry check specifically, not just an "unknown token" 404.
        final String rawToken = "expired-token-fixture";
        shareLinkRepository.save(new RoadmapShareLink(tenantId, teamId, projectId, sha256Hex(rawToken),
                Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThatExceptionOfType(ShareLinkAccessDeniedException.class).isThrownBy(() ->
                shareService.viewSharedRoadmap(rawToken));
    }

    @Test
    void unknownToken_view_deniedAccess() {
        assertThatExceptionOfType(ShareLinkAccessDeniedException.class).isThrownBy(() ->
                shareService.viewSharedRoadmap("this-token-was-never-issued"));
    }

    // -------- Security AC: the link only exposes the data of the roadmap it targets ---------------

    @Test
    void crossProject_view_neverLeaksOtherProjectData() {
        final Project otherProject = newProject(tenantId, teamId, "Other");
        roadmapService.createLane(tenantId, teamId, projectId, new CreateLaneRequest("Mine"));
        roadmapService.createLane(tenantId, teamId, otherProject.getId(), new CreateLaneRequest("Not mine"));

        final CreateShareLinkResponse created = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));

        final RoadmapShareViewResponse view = shareService.viewSharedRoadmap(created.token());

        assertThat(view.lanes()).extracting(LaneResponse::name).containsExactly("Mine");
    }

    // -------- Security AC: management is scoped to tenant/team/project -----------------------------

    @Test
    void crossTenant_createShareLink_throwsProjectNotFound() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                shareService.createShareLink(otherTenant, otherTeam, projectId, new CreateShareLinkRequest(null)));
    }

    @Test
    void revoke_scopedToWrongProject_throwsShareLinkNotFound() {
        final Project otherProject = newProject(tenantId, teamId, "Other");
        final CreateShareLinkResponse created = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));

        assertThatExceptionOfType(ShareLinkNotFoundException.class).isThrownBy(() ->
                shareService.revokeShareLink(tenantId, teamId, otherProject.getId(), created.id()));
    }

    // -------- Management listing -------------------------------------------------------------------

    @Test
    void listShareLinks_returnsAllLinksMostRecentFirst() {
        final CreateShareLinkResponse first = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));
        final CreateShareLinkResponse second = shareService.createShareLink(tenantId, teamId, projectId,
                new CreateShareLinkRequest(null));

        final List<ShareLinkResponse> links = shareService.listShareLinks(tenantId, teamId, projectId);

        assertThat(links).extracting(ShareLinkResponse::id).containsExactly(second.id(), first.id());
        assertThat(links).allMatch(ShareLinkResponse::active);
    }

    /**
     * Computes the lowercase hex-encoded SHA-256 digest of a raw token — test-only mirror of
     * {@code RoadmapShareService}'s private hashing so a test can persist a
     * {@link RoadmapShareLink} row directly (bypassing {@code createShareLink}) whose hash still
     * resolves for a known raw token, to exercise {@code viewSharedRoadmap}'s expiry/revocation
     * checks in isolation from token generation.
     *
     * @param rawToken the plaintext token to hash
     * @return 64-character hex string
     */
    private static String sha256Hex(final String rawToken) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }
}

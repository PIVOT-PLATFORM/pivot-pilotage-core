package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoadmapShareService} with mocked repositories/collaborators (US22.3.5) —
 * exercises every branch (project resolution, token generation/hashing, expiry validation,
 * revocation idempotency, public-view resolution) without a database, complementing
 * {@link RoadmapShareServiceIT}. Mirrors {@code fr.pivot.pilotage.roadmap.RoadmapServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class RoadmapShareServiceTest {

    private static final long TENANT = 7L;
    private static final long TEAM = 42L;
    private static final long PROJECT = 100L;

    @Mock private ProjectRepository projectRepository;
    @Mock private RoadmapShareLinkRepository shareLinkRepository;
    @Mock private RoadmapService roadmapService;

    private RoadmapShareService service;

    @BeforeEach
    void setUp() {
        service = new RoadmapShareService(projectRepository, shareLinkRepository, roadmapService);
        lenient().when(projectRepository.findByIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(new Project(null, TENANT, TEAM, "P", Instant.now())));
    }

    private static void setId(final Object entity, final long id) {
        try {
            final Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String sha256Hex(final String raw) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private static RoadmapShareLink link(final long id, final Instant expiresAt) {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT, TEAM, PROJECT, "h".repeat(64), expiresAt);
        setId(link, id);
        return link;
    }

    // ---- createShareLink ----------------------------------------------------------------------

    @Test
    void createShareLink_unknownProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndTenantIdAndTeamId(999L, TENANT, TEAM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                service.createShareLink(TENANT, TEAM, 999L, new CreateShareLinkRequest(null)));
    }

    @Test
    void createShareLink_pastExpiry_throwsInvalidShareLinkExpiryAndNeverSaves() {
        assertThatExceptionOfType(InvalidShareLinkExpiryException.class).isThrownBy(() ->
                service.createShareLink(TENANT, TEAM, PROJECT,
                        new CreateShareLinkRequest(Instant.now().minus(1, ChronoUnit.HOURS))));

        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    void createShareLink_presentExpiry_throwsInvalidShareLinkExpiry() {
        // "now" is not strictly in the future — boundary must be rejected, not accepted.
        assertThatExceptionOfType(InvalidShareLinkExpiryException.class).isThrownBy(() ->
                service.createShareLink(TENANT, TEAM, PROJECT, new CreateShareLinkRequest(Instant.now())));
    }

    @Test
    void createShareLink_noExpiry_persistsHashedTokenAndReturnsRawTokenOnce() {
        when(shareLinkRepository.save(any(RoadmapShareLink.class))).thenAnswer(inv -> {
            final RoadmapShareLink saved = inv.getArgument(0);
            setId(saved, 5L);
            saved.prePersist();
            return saved;
        });

        final CreateShareLinkResponse response = service.createShareLink(TENANT, TEAM, PROJECT,
                new CreateShareLinkRequest(null));

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.token()).hasSize(64);
        assertThat(response.expiresAt()).isNull();

        final ArgumentCaptor<RoadmapShareLink> captor = ArgumentCaptor.forClass(RoadmapShareLink.class);
        verify(shareLinkRepository).save(captor.capture());
        final RoadmapShareLink persisted = captor.getValue();

        // The persisted hash must match SHA-256(rawToken) — but must never equal the raw token
        // itself (raw token never persisted).
        assertThat(persisted.getTokenHash()).isEqualTo(sha256Hex(response.token()));
        assertThat(persisted.getTokenHash()).isNotEqualTo(response.token());
    }

    @Test
    void createShareLink_futureExpiry_persistsExpiry() {
        final Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        when(shareLinkRepository.save(any(RoadmapShareLink.class))).thenAnswer(inv -> {
            final RoadmapShareLink saved = inv.getArgument(0);
            setId(saved, 6L);
            return saved;
        });

        final CreateShareLinkResponse response = service.createShareLink(TENANT, TEAM, PROJECT,
                new CreateShareLinkRequest(expiresAt));

        assertThat(response.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void createShareLink_twoCallsGenerateDistinctTokens() {
        when(shareLinkRepository.save(any(RoadmapShareLink.class))).thenAnswer(inv -> {
            final RoadmapShareLink saved = inv.getArgument(0);
            setId(saved, 1L);
            return saved;
        });

        final String first = service.createShareLink(TENANT, TEAM, PROJECT, new CreateShareLinkRequest(null)).token();
        final String second = service.createShareLink(TENANT, TEAM, PROJECT, new CreateShareLinkRequest(null))
                .token();

        assertThat(first).isNotEqualTo(second);
    }

    // ---- listShareLinks -------------------------------------------------------------------------

    @Test
    void listShareLinks_unknownProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndTenantIdAndTeamId(999L, TENANT, TEAM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                service.listShareLinks(TENANT, TEAM, 999L));
    }

    @Test
    void listShareLinks_mapsEntitiesToResponseDtosNeverExposingTokenHash() {
        when(shareLinkRepository.findAllByProjectIdAndTenantIdAndTeamIdOrderByCreatedAtDesc(PROJECT, TENANT, TEAM))
                .thenReturn(List.of(link(1L, null), link(2L, Instant.now().plus(1, ChronoUnit.DAYS))));

        final List<ShareLinkResponse> result = service.listShareLinks(TENANT, TEAM, PROJECT);

        assertThat(result).extracting(ShareLinkResponse::id).containsExactly(1L, 2L);
        assertThat(result).allMatch(ShareLinkResponse::active);
    }

    // ---- revokeShareLink ------------------------------------------------------------------------

    @Test
    void revokeShareLink_unknownProject_throwsProjectNotFound() {
        when(projectRepository.findByIdAndTenantIdAndTeamId(999L, TENANT, TEAM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ProjectNotFoundException.class).isThrownBy(() ->
                service.revokeShareLink(TENANT, TEAM, 999L, 1L));
    }

    @Test
    void revokeShareLink_unknownShareLink_throwsShareLinkNotFound() {
        when(shareLinkRepository.findByIdAndProjectIdAndTenantIdAndTeamId(999L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ShareLinkNotFoundException.class).isThrownBy(() ->
                service.revokeShareLink(TENANT, TEAM, PROJECT, 999L));
    }

    @Test
    void revokeShareLink_activeLink_setsRevokedAtAndSaves() {
        final RoadmapShareLink active = link(1L, null);
        when(shareLinkRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(active));

        service.revokeShareLink(TENANT, TEAM, PROJECT, 1L);

        assertThat(active.getRevokedAt()).isNotNull();
        verify(shareLinkRepository).save(active);
    }

    @Test
    void revokeShareLink_alreadyRevoked_isIdempotentAndDoesNotSaveAgain() {
        final RoadmapShareLink alreadyRevoked = link(1L, null);
        final Instant originalRevokedAt = Instant.now().minus(1, ChronoUnit.DAYS);
        alreadyRevoked.setRevokedAt(originalRevokedAt);
        when(shareLinkRepository.findByIdAndProjectIdAndTenantIdAndTeamId(1L, PROJECT, TENANT, TEAM))
                .thenReturn(Optional.of(alreadyRevoked));

        service.revokeShareLink(TENANT, TEAM, PROJECT, 1L);

        assertThat(alreadyRevoked.getRevokedAt()).isEqualTo(originalRevokedAt);
        verify(shareLinkRepository, never()).save(any());
    }

    // ---- viewSharedRoadmap (public path) ----------------------------------------------------------

    @Test
    void viewSharedRoadmap_unknownToken_throwsShareLinkAccessDenied() {
        when(shareLinkRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatExceptionOfType(ShareLinkAccessDeniedException.class).isThrownBy(() ->
                service.viewSharedRoadmap("nonexistent-token"));
    }

    @Test
    void viewSharedRoadmap_revokedLink_throwsShareLinkAccessDenied() {
        final RoadmapShareLink revoked = link(1L, null);
        revoked.setRevokedAt(Instant.now());
        when(shareLinkRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        assertThatExceptionOfType(ShareLinkAccessDeniedException.class).isThrownBy(() ->
                service.viewSharedRoadmap("some-token"));

        verify(roadmapService, never()).listLanes(anyLong(), anyLong(), anyLong());
    }

    @Test
    void viewSharedRoadmap_expiredLink_throwsShareLinkAccessDenied() {
        final RoadmapShareLink expired = link(1L, Instant.now().minus(1, ChronoUnit.HOURS));
        when(shareLinkRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatExceptionOfType(ShareLinkAccessDeniedException.class).isThrownBy(() ->
                service.viewSharedRoadmap("some-token"));
    }

    @Test
    void viewSharedRoadmap_activeLinkButProjectVanished_throwsShareLinkAccessDeniedDefensively() {
        final RoadmapShareLink active = link(1L, null);
        when(shareLinkRepository.findByTokenHash(any())).thenReturn(Optional.of(active));
        when(projectRepository.findByIdAndTenantIdAndTeamId(PROJECT, TENANT, TEAM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ShareLinkAccessDeniedException.class).isThrownBy(() ->
                service.viewSharedRoadmap("some-token"));
    }

    @Test
    void viewSharedRoadmap_activeLink_returnsProjectNameLanesAndInitiatives() {
        final RoadmapShareLink active = link(1L, null);
        when(shareLinkRepository.findByTokenHash(any())).thenReturn(Optional.of(active));
        when(roadmapService.listLanes(TENANT, TEAM, PROJECT))
                .thenReturn(List.of(new LaneResponse(1L, "Theme A", 0)));
        when(roadmapService.listInitiatives(TENANT, TEAM, PROJECT)).thenReturn(List.of());

        final RoadmapShareViewResponse response = service.viewSharedRoadmap("some-token");

        assertThat(response.projectName()).isEqualTo("P");
        assertThat(response.lanes()).extracting(LaneResponse::name).containsExactly("Theme A");
        assertThat(response.initiatives()).isEmpty();
    }
}

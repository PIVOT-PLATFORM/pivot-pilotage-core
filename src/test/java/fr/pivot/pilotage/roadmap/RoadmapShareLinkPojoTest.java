package fr.pivot.pilotage.roadmap;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO unit tests for the US22.3.5 {@link RoadmapShareLink} entity — exercises the
 * constructor, accessors, lifecycle callback and the {@link RoadmapShareLink#isActive()}
 * liveness predicate without a Spring context or a database, mirroring
 * {@code fr.pivot.pilotage.roadmap.LanePojoTest}.
 */
class RoadmapShareLinkPojoTest {

    private static final Long TENANT_ID = 42L;
    private static final Long TEAM_ID = 99L;
    private static final Long PROJECT_ID = 7L;
    private static final String TOKEN_HASH = "a".repeat(64);

    @Test
    void fullConstructorInitializesAllFields() {
        final Instant expiresAt = Instant.now().plus(1, ChronoUnit.DAYS);
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH, expiresAt);

        assertThat(link.getId()).isNull();
        assertThat(link.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(link.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(link.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(link.getTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(link.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(link.getRevokedAt()).isNull();
    }

    @Test
    void prePersistSetsCreatedAtWhenUnset() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH, null);

        link.prePersist();

        assertThat(link.getCreatedAt()).isNotNull();
    }

    @Test
    void prePersistPreservesAlreadySetCreatedAt() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH, null);
        link.prePersist();
        final Instant firstCreated = link.getCreatedAt();

        link.prePersist();

        assertThat(link.getCreatedAt()).isEqualTo(firstCreated);
    }

    @Test
    void setRevokedAtUpdatesValue() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH, null);
        final Instant revokedAt = Instant.now();

        link.setRevokedAt(revokedAt);

        assertThat(link.getRevokedAt()).isEqualTo(revokedAt);
    }

    // ---- isActive() ---------------------------------------------------------------------------

    @Test
    void isActive_neverRevokedNoExpiry_isActive() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH, null);

        assertThat(link.isActive()).isTrue();
    }

    @Test
    void isActive_neverRevokedFutureExpiry_isActive() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH,
                Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(link.isActive()).isTrue();
    }

    @Test
    void isActive_neverRevokedPastExpiry_isNotActive() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH,
                Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(link.isActive()).isFalse();
    }

    @Test
    void isActive_revokedNoExpiry_isNotActive() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH, null);
        link.setRevokedAt(Instant.now());

        assertThat(link.isActive()).isFalse();
    }

    @Test
    void isActive_revokedWithFutureExpiry_isNotActive() {
        final RoadmapShareLink link = new RoadmapShareLink(TENANT_ID, TEAM_ID, PROJECT_ID, TOKEN_HASH,
                Instant.now().plus(1, ChronoUnit.HOURS));
        link.setRevokedAt(Instant.now());

        assertThat(link.isActive()).isFalse();
    }

    @Test
    void noArgConstructorAllowsLifecycleCallbacksBeforeFieldsAreSet() {
        // Exercises the protected no-arg constructor JPA requires (via reflection in production).
        final RoadmapShareLink blank = newBlankLink();

        blank.prePersist();

        assertThat(blank.getId()).isNull();
        assertThat(blank.getCreatedAt()).isNotNull();
    }

    private static RoadmapShareLink newBlankLink() {
        try {
            final var ctor = RoadmapShareLink.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("RoadmapShareLink must expose a no-arg constructor for JPA", e);
        }
    }
}

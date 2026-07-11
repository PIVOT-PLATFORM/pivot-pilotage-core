package fr.pivot.pilotage.roadmap;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO unit tests for the US22.3.1 {@link Lane} entity.
 *
 * <p>Exercises the constructor, getters, setters and lifecycle callbacks
 * ({@code @PrePersist}/{@code @PreUpdate}) without a Spring context or a database, covering the
 * "schema-only" accessor lines the Testcontainers integration tests do not reach, keeping the
 * module above the jacoco {@code LINE COVEREDRATIO} threshold (0.80) — mirrors
 * {@code fr.pivot.pilotage.project.ApplicationProjectPojoTest}.
 */
class LanePojoTest {

    private static final Long TENANT_ID = 42L;
    private static final Long TEAM_ID = 99L;
    private static final Long PROJECT_ID = 7L;

    @Test
    void fullConstructorInitializesAllFields() {
        final Lane lane = new Lane(TENANT_ID, TEAM_ID, PROJECT_ID, "Team Alpha", 0);

        assertThat(lane.getId()).isNull();
        assertThat(lane.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(lane.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(lane.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(lane.getName()).isEqualTo("Team Alpha");
        assertThat(lane.getPosition()).isZero();
    }

    @Test
    void settersUpdateNameAndPosition() {
        final Lane lane = new Lane(TENANT_ID, TEAM_ID, PROJECT_ID, "Old", 0);

        lane.setName("New name");
        lane.setPosition(3);

        assertThat(lane.getName()).isEqualTo("New name");
        assertThat(lane.getPosition()).isEqualTo(3);
    }

    @Test
    void prePersistSetsBothTimestampsWhenUnset() {
        final Lane lane = new Lane(TENANT_ID, TEAM_ID, PROJECT_ID, "Lane", 0);

        lane.prePersist();

        assertThat(lane.getCreatedAt()).isNotNull();
        assertThat(lane.getUpdatedAt()).isNotNull();
    }

    @Test
    void prePersistPreservesAlreadySetTimestampsAndPreUpdateRefreshesUpdatedAt() {
        final Lane lane = new Lane(TENANT_ID, TEAM_ID, PROJECT_ID, "Lane", 0);
        lane.prePersist();
        final Instant firstCreated = lane.getCreatedAt();
        final Instant firstUpdated = lane.getUpdatedAt();

        lane.prePersist();
        assertThat(lane.getCreatedAt()).isEqualTo(firstCreated);
        assertThat(lane.getUpdatedAt()).isEqualTo(firstUpdated);

        lane.preUpdate();
        assertThat(lane.getUpdatedAt()).isNotNull();
        assertThat(lane.getCreatedAt()).isEqualTo(firstCreated);
    }

    @Test
    void noArgConstructorAllowsLifecycleCallbacksBeforeFieldsAreSet() {
        // Exercises the protected no-arg constructor JPA requires (via reflection in production).
        final Lane blank = newBlankLane();

        blank.prePersist();

        assertThat(blank.getId()).isNull();
        assertThat(blank.getCreatedAt()).isNotNull();
        assertThat(blank.getUpdatedAt()).isNotNull();
    }

    private static Lane newBlankLane() {
        try {
            final var ctor = Lane.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("Lane must expose a no-arg constructor for JPA", e);
        }
    }
}

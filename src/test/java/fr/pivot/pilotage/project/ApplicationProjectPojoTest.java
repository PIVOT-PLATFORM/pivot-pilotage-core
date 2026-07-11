package fr.pivot.pilotage.project;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-POJO unit tests for the EN18.1 entities {@link Application} and {@link Project}.
 *
 * <p>These tests exercise every constructor, getter, setter, lifecycle callback
 * ({@code @PrePersist}/{@code @PreUpdate}) and the bidirectional relationship of both entities
 * without a Spring context or a database. They exist to cover the "schema-only" accessor lines
 * that the Testcontainers integration tests do not reach, keeping the module above the jacoco
 * {@code LINE COVEREDRATIO} threshold (0.80).
 */
class ApplicationProjectPojoTest {

    private static final Long TENANT_ID = 42L;
    private static final Long TEAM_ID = 99L;
    private static final Instant NOW = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void applicationFullConstructorInitializesAllFields() {
        final Application app = new Application(TENANT_ID, TEAM_ID, "Roadmap App", NOW);

        assertThat(app.getId()).isNull();
        assertThat(app.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(app.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(app.getName()).isEqualTo("Roadmap App");
        assertThat(app.getCreatedAt()).isEqualTo(NOW);
        assertThat(app.getUpdatedAt()).isEqualTo(NOW);
        assertThat(app.getProjects()).isEmpty();
    }

    @Test
    void applicationSetterUpdatesName() {
        final Application app = new Application(TENANT_ID, TEAM_ID, "Old", NOW);

        app.setName("New name");

        assertThat(app.getName()).isEqualTo("New name");
    }

    @Test
    void applicationPrePersistSetsBothTimestampsWhenUnset() {
        final Application app = new Application();

        app.prePersist();

        assertThat(app.getCreatedAt()).isNotNull();
        assertThat(app.getUpdatedAt()).isNotNull();
    }

    @Test
    void applicationPrePersistPreservesAlreadySetTimestamps() {
        final Application app = new Application(TENANT_ID, TEAM_ID, "Kept", NOW);

        app.prePersist();

        assertThat(app.getCreatedAt()).isEqualTo(NOW);
        assertThat(app.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void applicationPreUpdateRefreshesUpdatedAt() {
        final Application app = new Application(TENANT_ID, TEAM_ID, "App", NOW);

        app.preUpdate();

        assertThat(app.getUpdatedAt()).isAfter(NOW);
        assertThat(app.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void applicationAddProjectWiresBothSidesAndExposesUnmodifiableView() {
        final Application app = new Application(TENANT_ID, TEAM_ID, "App", NOW);
        final Project project = new Project(null, TENANT_ID, TEAM_ID, "Project A", NOW);

        app.addProject(project);

        assertThat(app.getProjects()).containsExactly(project);
        assertThat(project.getApplication()).isSameAs(app);

        final List<Project> view = app.getProjects();
        final Project other = new Project(null, TENANT_ID, TEAM_ID, "Project B", NOW);
        assertThatThrownBy(() -> view.add(other))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void projectFullConstructorInitializesAllFields() {
        final Application app = new Application(TENANT_ID, TEAM_ID, "Owner", NOW);
        final Project project = new Project(app, TENANT_ID, TEAM_ID, "Project A", NOW);

        assertThat(project.getId()).isNull();
        assertThat(project.getApplication()).isSameAs(app);
        assertThat(project.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(project.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(project.getName()).isEqualTo("Project A");
        assertThat(project.getCreatedAt()).isEqualTo(NOW);
        assertThat(project.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void projectSettersUpdateApplicationAndName() {
        final Project project = new Project(null, TENANT_ID, TEAM_ID, "Old", NOW);
        final Application app = new Application(TENANT_ID, TEAM_ID, "Owner", NOW);

        project.setApplication(app);
        project.setName("New name");

        assertThat(project.getApplication()).isSameAs(app);
        assertThat(project.getName()).isEqualTo("New name");
    }

    @Test
    void projectPrePersistSetsBothTimestampsWhenUnset() {
        final Project project = new Project();

        project.prePersist();

        assertThat(project.getCreatedAt()).isNotNull();
        assertThat(project.getUpdatedAt()).isNotNull();
    }

    @Test
    void projectPrePersistPreservesAlreadySetTimestamps() {
        final Project project = new Project(null, TENANT_ID, TEAM_ID, "Kept", NOW);

        project.prePersist();

        assertThat(project.getCreatedAt()).isEqualTo(NOW);
        assertThat(project.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void projectPreUpdateRefreshesUpdatedAt() {
        final Project project = new Project(null, TENANT_ID, TEAM_ID, "Project", NOW);

        project.preUpdate();

        assertThat(project.getUpdatedAt()).isAfter(NOW);
        assertThat(project.getCreatedAt()).isEqualTo(NOW);
    }
}

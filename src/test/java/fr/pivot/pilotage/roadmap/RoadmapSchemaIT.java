package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the US22.3.1 schema addition ({@code pilotage.lane} +
 * {@code pilotage.task.lane_id}) against a real PostgreSQL provided by Testcontainers, with this
 * module's Flyway migration applied — mirrors
 * {@code fr.pivot.pilotage.project.ApplicationProjectSchemaIT}'s DDL-assertion style.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RoadmapSchemaIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /**
     * Registers the container datasource properties and seeds the {@code public} schema before
     * the Spring context (and Flyway) start.
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
    private JdbcTemplate jdbcTemplate;

    @Test
    void ac_laneTableCreatedWithExpectedColumns() {
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'lane'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "tenant_id", "team_id", "project_id", "name", "position", "created_at", "updated_at");
    }

    @Test
    void ac_laneTenantTeamProjectColumnsAreNotNull() {
        for (final String column : List.of("tenant_id", "team_id", "project_id", "name", "position")) {
            final String nullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'pilotage' AND table_name = 'lane' AND column_name = ?",
                    String.class, column);
            assertThat(nullable).as("lane.%s should be NOT NULL", column).isEqualTo("NO");
        }
    }

    @Test
    void ac_laneHasUniqueConstraintOnProjectIdAndName() {
        final Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'pilotage.lane'::regclass
                  AND contype = 'u'
                  AND conname = 'uq_lane_project_name'
                """, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void ac_laneForeignKeysReferencePublicTenantsTeamsAndPilotageProject() {
        assertThat(fkCount("pilotage.lane", "tenant_id", "public.tenants")).isEqualTo(1);
        assertThat(fkCount("pilotage.lane", "team_id", "public.teams")).isEqualTo(1);
        assertThat(fkCount("pilotage.lane", "project_id", "pilotage.project")).isEqualTo(1);
    }

    @Test
    void ac_taskCarriesNullableLaneIdReferencingLane() {
        final String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'task' AND column_name = 'lane_id'",
                String.class);
        assertThat(nullable).isEqualTo("YES");
        assertThat(fkCount("pilotage.task", "lane_id", "pilotage.lane")).isEqualTo(1);
    }

    @Test
    void ac_taskLaneIdIsIndexed() {
        final Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'pilotage' AND tablename = 'task' AND indexname = 'idx_task_lane_id'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    /**
     * Counts single-column foreign keys from {@code (table, column)} towards {@code refTable} —
     * pg_catalog is authoritative for cross-schema FKs (information_schema hides them when the
     * referenced table is owned by another role), mirrors
     * {@code ApplicationProjectSchemaIT.ac_applicationPorteTenantIdNotNullFk}.
     */
    private Integer fkCount(final String table, final String column, final String refTable) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f'
                  AND c.conrelid = ?::regclass
                  AND c.confrelid = ?::regclass
                  AND a.attname = ?
                  AND array_length(c.conkey, 1) = 1
                """, Integer.class, table, refTable, column);
    }
}

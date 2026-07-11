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
 * Integration tests for the US22.3.5 schema addition ({@code pilotage.roadmap_share_link})
 * against a real PostgreSQL provided by Testcontainers, with this module's Flyway migration
 * applied — mirrors {@code fr.pivot.pilotage.roadmap.RoadmapSchemaIT}'s DDL-assertion style.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RoadmapShareSchemaIT {

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
    void ac_shareLinkTableCreatedWithExpectedColumns() {
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'roadmap_share_link'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "tenant_id", "team_id", "project_id", "token_hash", "created_at", "revoked_at", "expires_at");
    }

    @Test
    void ac_shareLinkRequiredColumnsAreNotNull() {
        for (final String column : List.of("tenant_id", "team_id", "project_id", "token_hash", "created_at")) {
            final String nullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'pilotage' AND table_name = 'roadmap_share_link' "
                            + "AND column_name = ?",
                    String.class, column);
            assertThat(nullable).as("roadmap_share_link.%s should be NOT NULL", column).isEqualTo("NO");
        }
    }

    @Test
    void ac_shareLinkOptionalColumnsAreNullable() {
        for (final String column : List.of("revoked_at", "expires_at")) {
            final String nullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'pilotage' AND table_name = 'roadmap_share_link' "
                            + "AND column_name = ?",
                    String.class, column);
            assertThat(nullable).as("roadmap_share_link.%s should be nullable", column).isEqualTo("YES");
        }
    }

    @Test
    void ac_shareLinkHasNoUpdatedAtColumn() {
        // Deliberate deviation from every other pilotage.* table in this file — a share link is
        // never "edited", only revoked (captured by revoked_at), mirroring AccessToken.
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'roadmap_share_link'",
                String.class);
        assertThat(columns).doesNotContain("updated_at");
    }

    @Test
    void ac_shareLinkHasUniqueConstraintOnTokenHash() {
        final Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'pilotage.roadmap_share_link'::regclass
                  AND contype = 'u'
                  AND conname = 'uq_roadmap_share_link_token_hash'
                """, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void ac_shareLinkForeignKeysReferencePublicTenantsTeamsAndPilotageProject() {
        assertThat(fkCount("pilotage.roadmap_share_link", "tenant_id", "public.tenants")).isEqualTo(1);
        assertThat(fkCount("pilotage.roadmap_share_link", "team_id", "public.teams")).isEqualTo(1);
        assertThat(fkCount("pilotage.roadmap_share_link", "project_id", "pilotage.project")).isEqualTo(1);
    }

    @Test
    void ac_shareLinkProjectForeignKeyCascadesOnDelete() {
        // Security AC enforcement at the schema level: a share link cannot outlive its project.
        final String deleteRule = jdbcTemplate.queryForObject("""
                SELECT confdeltype::text
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f'
                  AND c.conrelid = 'pilotage.roadmap_share_link'::regclass
                  AND c.confrelid = 'pilotage.project'::regclass
                  AND a.attname = 'project_id'
                """, String.class);
        assertThat(deleteRule).isEqualTo("c");
    }

    @Test
    void ac_shareLinkIndexesExistOnTenantTeamAndProject() {
        for (final String indexName : List.of("idx_roadmap_share_link_tenant_id", "idx_roadmap_share_link_team_id",
                "idx_roadmap_share_link_project_id")) {
            final Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_indexes "
                            + "WHERE schemaname = 'pilotage' AND tablename = 'roadmap_share_link' "
                            + "AND indexname = ?",
                    Integer.class, indexName);
            assertThat(count).as("index %s should exist", indexName).isEqualTo(1);
        }
    }

    /**
     * Counts single-column foreign keys from {@code (table, column)} towards {@code refTable} —
     * pg_catalog is authoritative for cross-schema FKs, mirrors
     * {@code RoadmapSchemaIT.fkCount}/{@code ApplicationProjectSchemaIT.ac_applicationPorteTenantIdNotNullFk}.
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

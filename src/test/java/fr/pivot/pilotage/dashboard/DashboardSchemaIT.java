package fr.pivot.pilotage.dashboard;

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
 * Integration tests for the US23.2.2 schema addition ({@code pilotage.dashboard_config} +
 * {@code pilotage.dashboard_widget}) against a real PostgreSQL provided by Testcontainers, with
 * this module's Flyway migration applied — mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapSchemaIT}'s DDL-assertion style.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DashboardSchemaIT {

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
    void ac_dashboardConfigTableCreatedWithExpectedColumns() {
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'dashboard_config'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "tenant_id", "team_id", "user_id", "profile", "view_mode", "created_at", "updated_at");
    }

    @Test
    void ac_dashboardConfigRequiredColumnsAreNotNull() {
        for (final String column : List.of("tenant_id", "team_id", "user_id", "profile", "view_mode")) {
            final String nullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'pilotage' AND table_name = 'dashboard_config' "
                            + "AND column_name = ?",
                    String.class, column);
            assertThat(nullable).as("dashboard_config.%s should be NOT NULL", column).isEqualTo("NO");
        }
    }

    @Test
    void ac_dashboardConfigUserIdHasNoForeignKey() {
        // Deliberate: pivot-pilotage-core/CLAUDE.md restricts cross-schema FK to tenants/teams only
        // (see DashboardConfig javadoc) — user_id must never gain a FK to public.users.
        final Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f'
                  AND c.conrelid = 'pilotage.dashboard_config'::regclass
                  AND a.attname = 'user_id'
                """, Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void ac_dashboardConfigForeignKeysReferencePublicTenantsAndTeams() {
        assertThat(fkCount("pilotage.dashboard_config", "tenant_id", "public.tenants")).isEqualTo(1);
        assertThat(fkCount("pilotage.dashboard_config", "team_id", "public.teams")).isEqualTo(1);
    }

    @Test
    void ac_dashboardConfigHasUniqueConstraintOnTenantTeamUser() {
        final Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'pilotage.dashboard_config'::regclass
                  AND contype = 'u'
                  AND conname = 'uq_dashboard_config_tenant_team_user'
                """, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void ac_dashboardWidgetTableCreatedWithExpectedColumns() {
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'dashboard_widget'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "tenant_id", "team_id", "dashboard_config_id", "application_id", "widget_type",
                "position", "grid_row", "grid_column", "grid_width", "grid_height", "created_at", "updated_at");
    }

    @Test
    void ac_dashboardWidgetForeignKeysReferenceExpectedTables() {
        assertThat(fkCount("pilotage.dashboard_widget", "tenant_id", "public.tenants")).isEqualTo(1);
        assertThat(fkCount("pilotage.dashboard_widget", "team_id", "public.teams")).isEqualTo(1);
        assertThat(fkCount("pilotage.dashboard_widget", "dashboard_config_id", "pilotage.dashboard_config"))
                .isEqualTo(1);
        assertThat(fkCount("pilotage.dashboard_widget", "application_id", "pilotage.application")).isEqualTo(1);
    }

    @Test
    void ac_dashboardWidgetCascadesOnDashboardConfigDeletion() {
        final String rule = jdbcTemplate.queryForObject("""
                SELECT confdeltype
                FROM pg_constraint
                WHERE conrelid = 'pilotage.dashboard_widget'::regclass
                  AND contype = 'f'
                  AND confrelid = 'pilotage.dashboard_config'::regclass
                """, String.class);
        assertThat(rule).isEqualTo("c"); // 'c' = CASCADE
    }

    @Test
    void ac_dashboardWidgetHasGridBoundsCheckConstraints() {
        final Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'pilotage.dashboard_widget'::regclass
                  AND contype = 'c'
                  AND conname IN ('chk_dashboard_widget_grid_column', 'chk_dashboard_widget_grid_width',
                                   'chk_dashboard_widget_grid_height', 'chk_dashboard_widget_grid_column_bounds')
                """, Integer.class);
        assertThat(count).isEqualTo(4);
    }

    /**
     * Counts single-column foreign keys from {@code (table, column)} towards {@code refTable} —
     * pg_catalog is authoritative for cross-schema FKs, mirrors {@code RoadmapSchemaIT.fkCount}.
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

package fr.pivot.pilotage.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Test-only support for seeding the {@code public} schema (owned by {@code pivot-core}, not by
 * this repo's own Flyway — which manages {@code pilotage} only) against a per-test-class
 * Testcontainers PostgreSQL instance.
 *
 * <p>{@code pilotage.application.tenant_id} and {@code pilotage.project.tenant_id} carry an FK to
 * {@code public.tenants(id)} (ADR-006/ADR-022). Because this module's Flyway only creates the
 * {@code pilotage} schema, the referenced {@code public.tenants} table must already exist before
 * Spring's context (and therefore Flyway) starts. {@link #createPublicSchema} is therefore
 * called once per container from the same {@code @DynamicPropertySource} static method that
 * registers the datasource properties.
 *
 * <p>Deliberately raw JDBC, not {@code JdbcTemplate}/Spring Data — this is test-only setup code
 * for a schema this repo's persistence layer never manages.
 */
public final class PlatformSchemaTestSupport {

    private PlatformSchemaTestSupport() {
    }

    /**
     * Creates the minimal {@code public.tenants} table this module's FKs reference. Idempotent
     * ({@code CREATE TABLE IF NOT EXISTS}) — safe to call once per container right after start.
     *
     * @param jdbcUrl  the Testcontainers-issued JDBC URL
     * @param username the database username
     * @param password the database password
     * @throws SQLException if the DDL fails
     */
    public static void createPublicSchema(final String jdbcUrl, final String username, final String password)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public.tenants (
                        id BIGSERIAL PRIMARY KEY,
                        slug VARCHAR(100) NOT NULL UNIQUE,
                        name VARCHAR(255) NOT NULL,
                        is_active BOOLEAN NOT NULL DEFAULT true,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }
    }

    /**
     * Inserts a tenant row and returns its generated id.
     *
     * @param jdbcUrl  the JDBC URL
     * @param username the database username
     * @param password the database password
     * @return the generated {@code public.tenants.id}
     * @throws SQLException if the insert fails
     */
    public static long seedTenant(final String jdbcUrl, final String username, final String password)
            throws SQLException {
        final String slug = "t-" + UUID.randomUUID();
        final String sql = "INSERT INTO public.tenants (slug, name) VALUES (?, ?) RETURNING id";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            ps.setString(2, slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}

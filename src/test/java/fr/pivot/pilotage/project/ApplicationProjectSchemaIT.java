package fr.pivot.pilotage.project;

import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the EN18.1 pilotage schema socle (Application -&gt; Project) against a
 * real PostgreSQL provided by Testcontainers, with this module's Flyway migration applied.
 *
 * <p>Each test is named after and maps to a frozen EN18.1 acceptance criterion. The
 * {@code public.tenants} table (owned by {@code pivot-core}) is seeded before the Spring context
 * and Flyway start, via {@link PlatformSchemaTestSupport}, so the FKs from {@code pilotage.*}
 * into {@code public.tenants(id)} resolve.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ApplicationProjectSchemaIT {

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
    private ApplicationRepository applicationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long teamId;

    /** Seeds a fresh tenant and team in {@code public.tenants}/{@code public.teams} before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        teamId = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), tenantId);
    }

    /**
     * AC: the {@code pilotage} schema and the {@code application}/{@code project} tables are
     * created by the migration (verified via {@code information_schema}).
     */
    @Test
    void ac_schemaEtTablesCreees() {
        final Integer schemaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pilotage'",
                Integer.class);
        assertThat(schemaCount).isEqualTo(1);

        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'pilotage' AND table_name IN ('application', 'project')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder("application", "project");
    }

    /**
     * AC: {@code pilotage.application.tenant_id} is {@code NOT NULL} and carries a foreign key to
     * {@code public.tenants(id)}.
     */
    @Test
    void ac_applicationPorteTenantIdNotNullFk() {
        final String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'application' "
                        + "AND column_name = 'tenant_id'",
                String.class);
        assertThat(nullable).isEqualTo("NO");

        // pg_catalog is authoritative here: information_schema.constraint_column_usage does not
        // surface a cross-schema FK whose referenced table (public.tenants) is owned by another
        // role — it returns zero rows for it (verified against PostgreSQL 18). pg_constraint has
        // no such visibility restriction.
        final Integer fkCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f'
                  AND c.conrelid = 'pilotage.application'::regclass
                  AND c.confrelid = 'public.tenants'::regclass
                  AND a.attname = 'tenant_id'
                  AND array_length(c.conkey, 1) = 1
                """, Integer.class);
        assertThat(fkCount).isEqualTo(1);
    }

    /**
     * AC (team_id retrofit): {@code pilotage.application.team_id} is {@code NOT NULL} and carries
     * a foreign key to {@code public.teams(id)} — mirrors {@link #ac_applicationPorteTenantIdNotNullFk()}.
     */
    @Test
    void ac_applicationPorteTeamIdNotNullFk() {
        final String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'application' "
                        + "AND column_name = 'team_id'",
                String.class);
        assertThat(nullable).isEqualTo("NO");

        final Integer fkCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f'
                  AND c.conrelid = 'pilotage.application'::regclass
                  AND c.confrelid = 'public.teams'::regclass
                  AND a.attname = 'team_id'
                  AND array_length(c.conkey, 1) = 1
                """, Integer.class);
        assertThat(fkCount).isEqualTo(1);
    }

    /**
     * AC: a project created against an application persists, inherits the application's tenant,
     * and is readable back through the repository.
     */
    @Test
    @Transactional
    void ac_projetRattacheApplicationMemeTenant() {
        final Instant now = Instant.now();
        final Application application = applicationRepository.save(
                new Application(tenantId, teamId, "App", now));

        final Project project = projectRepository.save(
                new Project(application, application.getTenantId(), application.getTeamId(), "Project", now));

        assertThat(project.getId()).isNotNull();
        assertThat(project.getTenantId()).isEqualTo(tenantId);

        final Project reloaded = projectRepository.findByIdAndTenantId(project.getId(), tenantId)
                .orElseThrow();
        assertThat(reloaded.getApplication().getId()).isEqualTo(application.getId());
        assertThat(reloaded.getTenantId()).isEqualTo(tenantId);
    }

    /**
     * AC: an application with two projects exposes the bidirectional collection {P1, P2}.
     */
    @Test
    @Transactional
    void ac_relationBidirectionnelle() {
        final Instant now = Instant.now();
        final Application application = new Application(tenantId, teamId, "App", now);
        application.addProject(new Project(application, tenantId, teamId, "P1", now));
        application.addProject(new Project(application, tenantId, teamId, "P2", now));

        final Application saved = applicationRepository.save(application);
        applicationRepository.flush();

        final Application reloaded = applicationRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getProjects())
                .hasSize(2)
                .extracting(Project::getName)
                .containsExactlyInAnyOrder("P1", "P2");
    }

    /**
     * AC: a project references exactly one application — {@code application_id} is
     * {@code NOT NULL}, so persisting a project without an application is rejected.
     */
    @Test
    void ac_unProjetUneApplication() {
        final String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'pilotage' AND table_name = 'project' "
                        + "AND column_name = 'application_id'",
                String.class);
        assertThat(nullable).isEqualTo("NO");

        // team_id is bound to the (valid, seeded) teamId so the failure is isolated to the
        // application_id under test, not a spurious NOT NULL violation on team_id.
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO pilotage.project (application_id, tenant_id, team_id, name) "
                                + "VALUES (NULL, ?, ?, 'Orphan')",
                        tenantId, teamId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * AC (error case): inserting a project referencing a non-existent {@code application_id}
     * violates referential integrity (SQLState 23503) and writes no row.
     */
    @Test
    void ac_erreur_fkApplicationInexistante() {
        final long missingApplicationId = 999_999_999L;

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO pilotage.project (application_id, tenant_id, team_id, name) "
                                + "VALUES (?, ?, ?, 'Ghost')",
                        missingApplicationId, tenantId, teamId))
                .isInstanceOf(DataIntegrityViolationException.class);

        final Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pilotage.project WHERE application_id = ?",
                Integer.class, missingApplicationId);
        assertThat(rows).isZero();
    }

    /**
     * AC (security): two tenants T1/T2 are isolated — a tenant-scoped repository read for T1
     * never returns rows belonging to T2. Cross-schema writes towards {@code public} are also
     * refused: {@code pilotage} rows referencing an unknown {@code public.tenants} id fail the FK
     * (a project can never smuggle in a tenant that does not exist in the platform schema).
     */
    @Test
    void ac_securite_isolationMultiTenant() throws Exception {
        final long tenantT2 = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        final long teamT2 = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), tenantT2);
        final Instant now = Instant.now();

        final Application appT1 = applicationRepository.save(new Application(tenantId, teamId, "AppT1", now));
        projectRepository.save(new Project(appT1, tenantId, teamId, "ProjT1", now));

        final Application appT2 = applicationRepository.save(new Application(tenantT2, teamT2, "AppT2", now));
        projectRepository.save(new Project(appT2, tenantT2, teamT2, "ProjT2", now));

        // In tenant T1's scope, no T2 data is visible.
        assertThat(applicationRepository.findAllByTenantId(tenantId))
                .extracting(Application::getTenantId)
                .containsOnly(tenantId);
        assertThat(projectRepository.findAllByTenantId(tenantId))
                .extracting(Project::getTenantId)
                .containsOnly(tenantId);
        assertThat(applicationRepository.findByIdAndTenantId(appT2.getId(), tenantId)).isEmpty();
        final Long projT2Id = projectRepository.findAllByTenantId(tenantT2).get(0).getId();
        assertThat(projectRepository.findByIdAndTenantId(projT2Id, tenantId)).isEmpty();

        // Cross-schema write towards public refused: unknown tenant id violates the FK.
        final long unknownTenantId = 888_888_888L;
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO pilotage.application (tenant_id, team_id, name) VALUES (?, ?, 'Rogue')",
                        unknownTenantId, teamId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

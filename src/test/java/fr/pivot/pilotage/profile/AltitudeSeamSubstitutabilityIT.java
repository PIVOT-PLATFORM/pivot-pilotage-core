package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import fr.pivot.pilotage.schedule.projection.Altitude;
import fr.pivot.pilotage.schedule.projection.DefaultAltitudeProvider;
import fr.pivot.pilotage.schedule.projection.FixedDefaultAltitudeProvider;
import fr.pivot.pilotage.schedule.projection.PlanNodeView;
import fr.pivot.pilotage.schedule.projection.PlanProjectionService;
import fr.pivot.pilotage.schedule.projection.PlanView;
import fr.pivot.pilotage.schedule.projection.ProfileBackedAltitudeProvider;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Substitutability tests for the EN18.10 seam (frozen contract §c). Two properties are checked:
 *
 * <ol>
 *   <li>the wired active {@link DefaultAltitudeProvider} bean is the profile-backed one
 *       ({@link ProfileBackedAltitudeProvider}), not the retired {@link FixedDefaultAltitudeProvider}
 *       component;</li>
 *   <li>the EN22.1c projection called without an explicit altitude produces the <em>same</em> view
 *       whether the seam is backed by the fixed provider (bootstrap) or the profile-backed provider
 *       (EN18.10) — the render cursor contract is unchanged (E22/E03 consumers untouched, the seam
 *       is substitutable by E40 later).</li>
 * </ol>
 *
 * <p>Anchored to a Monday in the past; no {@code now()} for domain dates.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AltitudeSeamSubstitutabilityIT {

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
    @Autowired private TaskRepository taskRepository;
    @Autowired private PlanProjectionService projectionService;
    @Autowired private DefaultAltitudeProvider activeProvider;
    @Autowired private OrganizationProfileResolver profileResolver;

    private long tenantId;
    private long teamId;

    private static final Instant MON_0900 =
            LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant();

    /** Seeds a fresh tenant and team before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        teamId = PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), tenantId);
    }

    // -------- AC: the active seam bean is the profile-backed provider --------------------------

    @Test
    void activeSeamIsProfileBacked() {
        assertThat(activeProvider).isInstanceOf(ProfileBackedAltitudeProvider.class);
        // Both backings agree on the default altitude for a real tenant (fixed macro == versioned
        // default macro) — this equality is exactly what makes the swap non-breaking.
        assertThat(activeProvider.defaultAltitude(tenantId)).isEqualTo(Altitude.MACRO);
        assertThat(new FixedDefaultAltitudeProvider().defaultAltitude(tenantId))
                .isEqualTo(Altitude.MACRO);
        assertThat(profileResolver.resolveProfile(tenantId).altitude()).isEqualTo(Altitude.MACRO);
    }

    // -------- AC: projection default view unchanged across the two backings ---------------------

    @Test
    void projectionDefault_identicalUnderBothBackings() {
        final Project project = newProject();
        final Task summary = taskRepository.save(new Task(tenantId, teamId, project.getId(), 0, "S",
                NodeKind.SUMMARY, false, TemporalPrecision.QUARTER, 0));
        final Task leaf = new Task(tenantId, teamId, project.getId(), 1, "L", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        leaf.setParentTaskId(summary.getId());
        leaf.setStartDate(MON_0900);
        leaf.setFinishDate(MON_0900);
        taskRepository.save(leaf);

        // Active (profile-backed) seam: default projection (no explicit altitude).
        final PlanView viaProfile = projectionService.project(project.getId(), tenantId).orElseThrow();

        // Same service, but forcing the exact altitude the fixed bootstrap provider would supply.
        final Altitude fixedDefault = new FixedDefaultAltitudeProvider().defaultAltitude(tenantId);
        final PlanView viaFixed = projectionService
                .project(project.getId(), tenantId, fixedDefault,
                        fixedDefault == Altitude.MACRO
                                ? fr.pivot.pilotage.schedule.projection.Layout.TIMELINE
                                : fr.pivot.pilotage.schedule.projection.Layout.GANTT)
                .orElseThrow();

        assertThat(viaProfile.altitude()).isEqualTo(viaFixed.altitude()).isEqualTo(Altitude.MACRO);
        assertThat(viaProfile.layout()).isEqualTo(viaFixed.layout());
        assertThat(ids(viaProfile)).isEqualTo(ids(viaFixed));
        // Macro view folds leaves under the summary — only the SUMMARY node is visible.
        assertThat(ids(viaProfile)).containsExactly(summary.getId());
    }

    private static List<Long> ids(final PlanView view) {
        return view.nodes().stream().map(PlanNodeView::nodeId).toList();
    }

    private Project newProject() {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(tenantId, teamId, "App", now));
        return projectRepository.save(new Project(app, tenantId, teamId, "P", now));
    }
}

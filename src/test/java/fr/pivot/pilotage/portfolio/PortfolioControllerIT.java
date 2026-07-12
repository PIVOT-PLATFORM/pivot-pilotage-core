package fr.pivot.pilotage.portfolio;

import fr.pivot.pilotage.consolidation.ApplicationConsolidation;
import fr.pivot.pilotage.consolidation.ProjectPlanningStatus;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link PortfolioController} (US23.2.1) — full Spring context against a real
 * PostgreSQL 18 (Testcontainers, required only to boot the context/Flyway), with
 * {@link PortfolioConsolidationService} and {@link PortfolioReadPolicy} mocked via
 * {@code @MockitoBean} — mirrors {@code fr.pivot.pilotage.profile.OrganizationProfileControllerIT}'s
 * established pattern. No actual application/project row is written here — that is
 * {@link PortfolioConsolidationServiceIT}'s job.
 *
 * <p>Covers: authorized read → 200 with the service result as JSON (AC: santé/avancement/phases/
 * jalons/dates clés surfaced); unauthorized → 403 and the service never invoked (security AC).
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths used here start with
 * {@code /tenants/...}, not {@code /api/pilotage/tenants/...}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PortfolioControllerIT {

    private static final long TENANT = 42L;
    private static final String BASE_PATH = "/tenants/" + TENANT + "/portfolio";

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

    @Autowired
    private WebApplicationContext wac;

    @MockitoBean
    private PortfolioConsolidationService portfolioConsolidationService;

    @MockitoBean
    private PortfolioReadPolicy readPolicy;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // -------- AC: consolidated view returned as JSON (santé/avancement/phases/jalons/dates) ------

    @Test
    void getPortfolio_authorized_returns200AndInvokesService() throws Exception {
        when(readPolicy.isAuthorized()).thenReturn(true);
        final ApplicationConsolidation consolidation = new ApplicationConsolidation(1L, "Billing", TENANT, 1,
                Map.of(ProjectPlanningStatus.SCHEDULED, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                List.of(), List.of());
        final PortfolioProjectEntry projectEntry = new PortfolioProjectEntry(10L, "v1", 5L,
                ProjectPlanningStatus.SCHEDULED, ProjectHealthIndicator.notSet(), BigDecimal.valueOf(42),
                List.of(new PortfolioPhaseEntry(100L, "Cadrage", 0)));
        final PortfolioApplicationEntry appEntry =
                new PortfolioApplicationEntry(1L, "Billing", consolidation, List.of(projectEntry));
        when(portfolioConsolidationService.consolidate(TENANT))
                .thenReturn(new PortfolioResponse(TENANT, List.of(appEntry)));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.applications[0].applicationName").value("Billing"))
                .andExpect(jsonPath("$.applications[0].projects[0].projectName").value("v1"))
                .andExpect(jsonPath("$.applications[0].projects[0].teamId").value(5))
                .andExpect(jsonPath("$.applications[0].projects[0].planningStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.applications[0].projects[0].health.status").value("NOT_SET"))
                .andExpect(jsonPath("$.applications[0].projects[0].progressPercent").value(42))
                .andExpect(jsonPath("$.applications[0].projects[0].phases[0].name").value("Cadrage"));

        verify(portfolioConsolidationService).consolidate(TENANT);
    }

    // -------- Security AC: no read right → 403, service never invoked -----------------------------

    @Test
    void getPortfolio_notAuthorized_returns403AndNeverInvokesService() throws Exception {
        when(readPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(get(BASE_PATH)).andExpect(status().isForbidden());

        verify(portfolioConsolidationService, never()).consolidate(TENANT);
    }
}

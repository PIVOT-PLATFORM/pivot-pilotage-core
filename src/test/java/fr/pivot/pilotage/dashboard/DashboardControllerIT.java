package fr.pivot.pilotage.dashboard;

import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link DashboardController} (US23.2.2) — full Spring context against a real
 * PostgreSQL 18 (Testcontainers, required only to boot the context/Flyway), with
 * {@link DashboardService} mocked via {@code @MockitoBean} — mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapControllerIT}'s established pattern. No actual dashboard
 * row is written here — that is {@link DashboardServiceIT}'s job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DashboardControllerIT {

    private static final long TENANT = 42L;
    private static final long TEAM = 5L;
    private static final long USER = 7L;
    private static final String BASE_PATH = "/tenants/" + TENANT + "/teams/" + TEAM + "/users/" + USER + "/dashboard";

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

    @Autowired private WebApplicationContext wac;

    @MockitoBean
    private DashboardService dashboardService;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // -------- GET: renders the service result as JSON ---------------------------------------------

    @Test
    void getDashboard_returnsServiceResultAsJson() throws Exception {
        final DashboardIndicatorView indicator = new DashboardIndicatorView(IndicatorStatus.AVAILABLE,
                AlertLevel.WARNING, "Retard", 3, Map.of("SCHEDULED", 3), null);
        final DashboardWidgetResponse widget = new DashboardWidgetResponse(1L,
                DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY, 100L, 0, 0, 0, 2, 1, indicator);
        when(dashboardService.getDashboard(TENANT, TEAM, USER)).thenReturn(
                new DashboardResponse(USER, "PMO", DashboardViewMode.SYNTHETIC, List.of(widget), null));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("PMO"))
                .andExpect(jsonPath("$.viewMode").value("SYNTHETIC"))
                .andExpect(jsonPath("$.widgets[0].widgetType").value("PORTFOLIO_STATUS_SUMMARY"))
                .andExpect(jsonPath("$.widgets[0].indicator.alertLevel").value("WARNING"))
                .andExpect(jsonPath("$.widgets[0].indicator.alertLabel").value("Retard"));
    }

    @Test
    void getDashboard_neverConfigured_returns200WithDefaultShape() throws Exception {
        when(dashboardService.getDashboard(TENANT, TEAM, USER))
                .thenReturn(new DashboardResponse(USER, null, DashboardViewMode.SYNTHETIC, List.of(), null));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").doesNotExist())
                .andExpect(jsonPath("$.widgets").isEmpty());
    }

    // -------- PUT: saves and returns the rendered dashboard -----------------------------------------

    @Test
    void saveDashboard_valid_returns200AndInvokesService() throws Exception {
        when(dashboardService.saveDashboard(eq(TENANT), eq(TEAM), eq(USER), any(SaveDashboardRequest.class)))
                .thenReturn(new DashboardResponse(USER, "PMO", DashboardViewMode.DETAILED, List.of(), null));

        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"PMO\",\"viewMode\":\"DETAILED\",\"widgets\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewMode").value("DETAILED"));
    }

    @Test
    void saveDashboard_blankProfile_returns400() throws Exception {
        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"\",\"viewMode\":\"DETAILED\",\"widgets\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveDashboard_missingViewMode_returns400WithBody() throws Exception {
        when(dashboardService.saveDashboard(eq(TENANT), eq(TEAM), eq(USER), any(SaveDashboardRequest.class)))
                .thenThrow(new InvalidDashboardConfigException(
                        InvalidDashboardConfigException.CODE_VIEW_MODE_REQUIRED, "A view mode is required"));

        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"PMO\",\"widgets\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VIEW_MODE_REQUIRED"));
    }

    @Test
    void saveDashboard_invalidWidget_returns400WithBody() throws Exception {
        when(dashboardService.saveDashboard(eq(TENANT), eq(TEAM), eq(USER), any(SaveDashboardRequest.class)))
                .thenThrow(new InvalidDashboardWidgetException(
                        InvalidDashboardWidgetException.CODE_WIDGET_DISPOSITION_OUT_OF_BOUNDS,
                        "Widget disposition out of bounds"));

        mockMvc.perform(put(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"PMO\",\"viewMode\":\"SYNTHETIC\",\"widgets\":"
                                + "[{\"widgetType\":\"WEATHER_ALERTS\",\"applicationId\":1,\"position\":0,"
                                + "\"gridRow\":0,\"gridColumn\":9,\"gridWidth\":1,\"gridHeight\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WIDGET_DISPOSITION_OUT_OF_BOUNDS"));
    }

    // -------- Security: two different userIds hit two different, independent resources -------------

    @Test
    void getDashboard_differentUserIdPath_invokesServiceWithThatDistinctUserId() throws Exception {
        final long otherUser = 999L;
        when(dashboardService.getDashboard(TENANT, TEAM, otherUser))
                .thenReturn(new DashboardResponse(otherUser, null, DashboardViewMode.SYNTHETIC, List.of(), null));

        mockMvc.perform(get("/tenants/" + TENANT + "/teams/" + TEAM + "/users/" + otherUser + "/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(otherUser))
                .andExpect(jsonPath("$.widgets").isEmpty());
    }
}

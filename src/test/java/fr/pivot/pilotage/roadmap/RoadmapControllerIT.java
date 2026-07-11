package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.TemporalPrecision;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link RoadmapController} (US22.3.1) — full Spring context against a real
 * PostgreSQL 18 (Testcontainers, required only to boot the context/Flyway), with
 * {@link RoadmapService} and {@link RoadmapEditPolicy} mocked via {@code @MockitoBean} — mirrors
 * {@code fr.pivot.pilotage.profile.OrganizationProfileControllerIT}'s established pattern. No
 * actual lane/initiative row is written here — that is {@link RoadmapServiceIT}'s job.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths used here start with
 * {@code /tenants/...}, not {@code /api/pilotage/tenants/...}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RoadmapControllerIT {

    private static final long TENANT = 42L;
    private static final long TEAM = 5L;
    private static final long PROJECT = 100L;
    private static final String BASE_PATH = "/tenants/" + TENANT + "/teams/" + TEAM + "/projects/" + PROJECT + "/roadmap";

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
    private RoadmapService roadmapService;

    @MockitoBean
    private RoadmapEditPolicy editPolicy;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // -------- lanes: read -------------------------------------------------------------------------

    @Test
    void listLanes_returnsServiceResultAsJson() throws Exception {
        when(roadmapService.listLanes(TENANT, TEAM, PROJECT))
                .thenReturn(List.of(new LaneResponse(1L, "Theme A", 0)));

        mockMvc.perform(get(BASE_PATH + "/lanes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Theme A"));
    }

    @Test
    void listLanes_unknownProject_returns404() throws Exception {
        when(roadmapService.listLanes(TENANT, TEAM, PROJECT))
                .thenThrow(new ProjectNotFoundException(PROJECT, TENANT, TEAM));

        mockMvc.perform(get(BASE_PATH + "/lanes")).andExpect(status().isNotFound());
    }

    // -------- lanes: create -----------------------------------------------------------------------

    @Test
    void createLane_authorized_returns201AndInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(roadmapService.createLane(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateLaneRequest.class)))
                .thenReturn(new LaneResponse(1L, "Theme A", 0));

        mockMvc.perform(post(BASE_PATH + "/lanes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Theme A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        verify(roadmapService).createLane(eq(TENANT), eq(TEAM), eq(PROJECT),
                eq(new CreateLaneRequest("Theme A")));
    }

    @Test
    void createLane_notAuthorized_returns403AndNeverInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE_PATH + "/lanes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Theme A\"}"))
                .andExpect(status().isForbidden());

        verify(roadmapService, never()).createLane(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void createLane_blankName_returns400() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(post(BASE_PATH + "/lanes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(roadmapService, never()).createLane(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void createLane_duplicateName_returns409WithBody() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(roadmapService.createLane(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateLaneRequest.class)))
                .thenThrow(new DuplicateLaneNameException("Theme A", PROJECT));

        mockMvc.perform(post(BASE_PATH + "/lanes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Theme A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LANE_DUPLICATE"));
    }

    // -------- initiatives: read -------------------------------------------------------------------

    @Test
    void listInitiatives_returnsServiceResultAsJson() throws Exception {
        when(roadmapService.listInitiatives(TENANT, TEAM, PROJECT)).thenReturn(List.of(
                new InitiativeResponse(1L, 1L, "Launch v1", null, null, TemporalPrecision.QUARTER, 0)));

        mockMvc.perform(get(BASE_PATH + "/initiatives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Launch v1"));
    }

    // -------- initiatives: create -------------------------------------------------------------------

    @Test
    void createInitiative_authorized_returns201AndInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(roadmapService.createInitiative(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateInitiativeRequest.class)))
                .thenReturn(new InitiativeResponse(1L, 1L, "Launch v1", null, null, TemporalPrecision.QUARTER, 0));

        mockMvc.perform(post(BASE_PATH + "/initiatives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Launch v1\",\"laneId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.laneId").value(1));
    }

    @Test
    void createInitiative_notAuthorized_returns403AndNeverInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE_PATH + "/initiatives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Launch v1\",\"laneId\":1}"))
                .andExpect(status().isForbidden());

        verify(roadmapService, never()).createInitiative(anyLong(), anyLong(), anyLong(), any());
    }

    // -------- Error case: an initiative without a target lane is rejected with a message ----------

    @Test
    void createInitiative_missingLaneId_returns400WithLaneRequiredMessage() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(roadmapService.createInitiative(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateInitiativeRequest.class)))
                .thenThrow(LaneNotFoundException.missing(PROJECT));

        mockMvc.perform(post(BASE_PATH + "/initiatives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Launch v1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LANE_REQUIRED"))
                .andExpect(content().string(containsString("lane")));

        verify(roadmapService).createInitiative(eq(TENANT), eq(TEAM), eq(PROJECT),
                eq(new CreateInitiativeRequest("Launch v1", null, null, null, null)));
    }

    @Test
    void createInitiative_invalidLane_returns400WithBody() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(roadmapService.createInitiative(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateInitiativeRequest.class)))
                .thenThrow(LaneNotFoundException.invalid(999L, PROJECT));

        mockMvc.perform(post(BASE_PATH + "/initiatives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Launch v1\",\"laneId\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LANE_NOT_FOUND"));
    }

    @Test
    void createInitiative_invalidPeriod_returns400WithBody() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(roadmapService.createInitiative(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateInitiativeRequest.class)))
                .thenThrow(new InvalidInitiativePeriodException("fuzzyPeriodEnd must not precede fuzzyPeriodStart"));

        mockMvc.perform(post(BASE_PATH + "/initiatives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Launch v1\",\"laneId\":1,"
                                + "\"fuzzyPeriodStart\":\"2026-03-31\",\"fuzzyPeriodEnd\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    // -------- initiatives: placement update -----------------------------------------------------

    @Test
    void updatePlacement_authorized_returns200AndInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(roadmapService.updatePlacement(eq(TENANT), eq(TEAM), eq(PROJECT), eq(7L),
                any(UpdateInitiativePlacementRequest.class)))
                .thenReturn(new InitiativeResponse(7L, 1L, "Launch v1",
                        java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 3, 31),
                        TemporalPrecision.QUARTER, 1));

        mockMvc.perform(patch(BASE_PATH + "/initiatives/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fuzzyPeriodStart\":\"2026-01-01\",\"fuzzyPeriodEnd\":\"2026-03-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void updatePlacement_notAuthorized_returns403AndNeverInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(patch(BASE_PATH + "/initiatives/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(roadmapService, never()).updatePlacement(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void updatePlacement_unknownInitiative_returns404() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        doThrow(new InitiativeNotFoundException(999L, PROJECT))
                .when(roadmapService).updatePlacement(eq(TENANT), eq(TEAM), eq(PROJECT), eq(999L), any());

        mockMvc.perform(patch(BASE_PATH + "/initiatives/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}

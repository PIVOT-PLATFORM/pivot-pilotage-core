package fr.pivot.pilotage.baseline;

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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link BaselineController} (US22.4.9) — full Spring context against a real
 * PostgreSQL 18 (Testcontainers, only to boot the context/Flyway), with {@link BaselineService} and
 * {@link BaselineEditPolicy} mocked via {@code @MockitoBean}. Mirrors {@code WbsTaskControllerIT}.
 * Covers the HTTP contract: the 403 write gate (pose/overwrite/delete — PMO/chef de projet only),
 * the read endpoints staying reachable even when the edit policy denies (security AC — a
 * contributeur planning may still consult écarts), the 404 non-disclosure, and the 409/422 error
 * bodies. Row-level behaviour is {@link BaselineServiceIT}'s job.
 *
 * <p>MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly, without
 * the {@code server.servlet.context-path} prefix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BaselineControllerIT {

    private static final long TENANT = 42L;
    private static final long TEAM = 5L;
    private static final long PROJECT = 100L;
    private static final String BASE =
            "/tenants/" + TENANT + "/teams/" + TEAM + "/projects/" + PROJECT + "/baselines";

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
    private BaselineService baselineService;

    @MockitoBean
    private BaselineEditPolicy editPolicy;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private static BaselineResponse sampleBaseline() {
        return new BaselineResponse(1L, (short) 0, Instant.parse("2026-01-01T00:00:00Z"), 3);
    }

    // -------- read: list, not gated -----------------------------------------------------------------

    @Test
    void list_returnsServiceResultAsJson() throws Exception {
        when(baselineService.listBaselines(TENANT, TEAM, PROJECT)).thenReturn(List.of(sampleBaseline()));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].baselineIndex").value(0))
                .andExpect(jsonPath("$[0].taskCount").value(3));
    }

    @Test
    void list_unknownProject_returns404() throws Exception {
        when(baselineService.listBaselines(TENANT, TEAM, PROJECT))
                .thenThrow(new BaselineProjectNotFoundException(PROJECT, TENANT, TEAM));

        mockMvc.perform(get(BASE)).andExpect(status().isNotFound());
    }

    // -------- write: pose/overwrite, gated (security AC — PMO/chef de projet only) ------------------

    @Test
    void setBaseline_authorized_returns201AndInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(baselineService.setBaseline(eq(TENANT), eq(TEAM), eq(PROJECT), any()))
                .thenReturn(sampleBaseline());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baselineIndex").value(0));
    }

    @Test
    void setBaseline_noBody_autoAssignsNullIndex() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(baselineService.setBaseline(TENANT, TEAM, PROJECT, null)).thenReturn(sampleBaseline());

        mockMvc.perform(post(BASE)).andExpect(status().isCreated());

        verify(baselineService).setBaseline(TENANT, TEAM, PROJECT, null);
    }

    @Test
    void setBaseline_unauthorized_returns403_andServiceNeverCalled() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verify(baselineService, never()).setBaseline(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void setBaseline_limitExceeded_returns409WithBody() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(baselineService.setBaseline(eq(TENANT), eq(TEAM), eq(PROJECT), any()))
                .thenThrow(new BaselineLimitExceededException(PROJECT));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BASELINE_LIMIT_EXCEEDED"));
    }

    @Test
    void setBaseline_invalidIndex_returns422WithBody() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(baselineService.setBaseline(eq(TENANT), eq(TEAM), eq(PROJECT), any()))
                .thenThrow(InvalidBaselineIndexException.outOfRange((short) 11));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"baselineIndex\":11}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_BASELINE_INDEX"));
    }

    // -------- write: delete, gated ------------------------------------------------------------------

    @Test
    void deleteBaseline_authorized_returns204() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(delete(BASE + "/0")).andExpect(status().isNoContent());

        verify(baselineService).deleteBaseline(TENANT, TEAM, PROJECT, (short) 0);
    }

    @Test
    void deleteBaseline_unauthorized_returns403_andServiceNeverCalled() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(delete(BASE + "/0")).andExpect(status().isForbidden());

        verify(baselineService, never()).deleteBaseline(anyLong(), anyLong(), anyLong(), anyShort());
    }

    @Test
    void deleteBaseline_unknownIndex_returns404() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        org.mockito.Mockito.doThrow(new BaselineNotFoundException(PROJECT, (short) 9))
                .when(baselineService).deleteBaseline(TENANT, TEAM, PROJECT, (short) 9);

        mockMvc.perform(delete(BASE + "/9")).andExpect(status().isNotFound());
    }

    // -------- read: variance/compare, NOT gated (security AC — contributeur planning consults) ------

    @Test
    void variance_readableEvenWhenEditPolicyDenies_returns200() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);
        when(baselineService.variance(TENANT, TEAM, PROJECT, (short) 0))
                .thenReturn(new BaselineVarianceResponse((short) 0, Instant.parse("2026-01-01T00:00:00Z"),
                        List.of()));

        mockMvc.perform(get(BASE + "/0/variance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineIndex").value(0));
    }

    @Test
    void variance_unknownBaseline_returns404() throws Exception {
        when(baselineService.variance(TENANT, TEAM, PROJECT, (short) 4))
                .thenThrow(new BaselineNotFoundException(PROJECT, (short) 4));

        mockMvc.perform(get(BASE + "/4/variance")).andExpect(status().isNotFound());
    }

    @Test
    void compare_readableEvenWhenEditPolicyDenies_returns200() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);
        when(baselineService.compare(TENANT, TEAM, PROJECT, (short) 0, (short) 1))
                .thenReturn(new BaselineComparisonResponse((short) 0, Instant.parse("2026-01-01T00:00:00Z"),
                        (short) 1, Instant.parse("2026-02-01T00:00:00Z"), List.of()));

        mockMvc.perform(get(BASE + "/0/compare/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromIndex").value(0))
                .andExpect(jsonPath("$.toIndex").value(1));
    }

    @Test
    void compare_unknownBaseline_returns404() throws Exception {
        when(baselineService.compare(TENANT, TEAM, PROJECT, (short) 0, (short) 7))
                .thenThrow(new BaselineNotFoundException(PROJECT, (short) 7));

        mockMvc.perform(get(BASE + "/0/compare/7")).andExpect(status().isNotFound());
    }
}

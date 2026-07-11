package fr.pivot.pilotage.profile;

import fr.pivot.pilotage.schedule.projection.Altitude;
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

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link OrganizationProfileController} (EN18.10 écart #3) — full Spring context
 * (no lightweight MVC test slice is available in this Spring Boot 4.x setup, cf.
 * {@code pivot-collaboratif-core}'s {@code WhiteboardTemplateControllerIT}, the established
 * pattern this test mirrors) against a real PostgreSQL 18 (Testcontainers), with the write-path
 * service ({@link OrganizationProfileOverrideService}) and the role gate
 * ({@link OrganizationProfileOverridePolicy}) mocked via {@code @MockitoBean} — no actual override
 * row is written here, that is {@link OrganizationProfileOverrideServiceIT}'s job.
 *
 * <p>Covers: authorized → 204 and the service invoked with the exact (tenantId, request) pair;
 * unauthorized → 403 and the service never invoked (the role gate must short-circuit before any
 * write); a request missing a required field → 400 (bean validation); the service's domain
 * exceptions mapped to 404 by {@link OrganizationProfileExceptionHandler}.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths used here start with
 * {@code /organization-profile}, not {@code /api/pilotage/organization-profile}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class OrganizationProfileControllerIT {

    private static final String BASE_PATH = "/organization-profile";

    private static final String VALID_BODY = """
            {
              "teamId": 5,
              "altitude": "MACRO",
              "sovereigntyClass": "ZONE_B_CONTROLEE",
              "rigorLevel": "STANDARD",
              "defaultModules": ["roadmap"]
            }
            """;

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
    private OrganizationProfileOverrideService overrideService;

    @MockitoBean
    private OrganizationProfileOverridePolicy overridePolicy;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // -------- AC: authorized → 204, service invoked with the exact (tenantId, request) pair -----

    @Test
    void putOverride_authorized_returns204AndInvokesService() throws Exception {
        when(overridePolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(put(BASE_PATH + "/{tenantId}", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        verify(overrideService).upsertOverride(eq(42L), any(OrganizationProfileOverrideRequest.class));
    }

    /**
     * Belt-and-suspenders against a silently-wrong JSON binding: the mocked service must receive
     * the exact deserialized field values, not just "any request".
     */
    @Test
    void putOverride_deserializesRequestFieldsCorrectly() throws Exception {
        when(overridePolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(put(BASE_PATH + "/{tenantId}", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        verify(overrideService).upsertOverride(eq(42L), eq(new OrganizationProfileOverrideRequest(
                5L, Altitude.MACRO, SovereigntyClass.ZONE_B_CONTROLEE, RigorLevel.STANDARD,
                Set.of("roadmap"))));
    }

    // -------- Security AC: unauthorized → 403, service never invoked --------------------------

    @Test
    void putOverride_notAuthorized_returns403AndNeverInvokesService() throws Exception {
        when(overridePolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(put(BASE_PATH + "/{tenantId}", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verify(overrideService, never()).upsertOverride(anyLong(), any());
    }

    // -------- Error case: a request missing a required field → 400 -----------------------------

    @Test
    void putOverride_missingTeamId_returns400() throws Exception {
        when(overridePolicy.isAuthorized()).thenReturn(true);
        final String missingTeamId = """
                {
                  "altitude": "MACRO",
                  "sovereigntyClass": "ZONE_B_CONTROLEE",
                  "rigorLevel": "STANDARD",
                  "defaultModules": ["roadmap"]
                }
                """;

        mockMvc.perform(put(BASE_PATH + "/{tenantId}", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingTeamId))
                .andExpect(status().isBadRequest());

        verify(overrideService, never()).upsertOverride(anyLong(), any());
    }

    @Test
    void putOverride_emptyDefaultModules_returns400() throws Exception {
        when(overridePolicy.isAuthorized()).thenReturn(true);
        final String emptyModules = """
                {
                  "teamId": 5,
                  "altitude": "MACRO",
                  "sovereigntyClass": "ZONE_B_CONTROLEE",
                  "rigorLevel": "STANDARD",
                  "defaultModules": []
                }
                """;

        mockMvc.perform(put(BASE_PATH + "/{tenantId}", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyModules))
                .andExpect(status().isBadRequest());
    }

    // -------- Error case: unknown tenant → 404 (never confirms existence) ----------------------

    @Test
    void putOverride_unknownTenant_returns404() throws Exception {
        when(overridePolicy.isAuthorized()).thenReturn(true);
        doThrow(new TenantNotFoundException(42L))
                .when(overrideService).upsertOverride(anyLong(), any(OrganizationProfileOverrideRequest.class));

        mockMvc.perform(put(BASE_PATH + "/{tenantId}", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    // -------- Error case: unknown/cross-tenant team → 404 (same non-disclosure posture) --------

    @Test
    void putOverride_unknownTeam_returns404() throws Exception {
        when(overridePolicy.isAuthorized()).thenReturn(true);
        doThrow(new TeamNotFoundException(5L, 42L))
                .when(overrideService).upsertOverride(anyLong(), any(OrganizationProfileOverrideRequest.class));

        mockMvc.perform(put(BASE_PATH + "/{tenantId}", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound());
    }
}

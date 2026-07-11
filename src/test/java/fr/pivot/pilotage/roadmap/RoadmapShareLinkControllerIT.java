package fr.pivot.pilotage.roadmap;

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
 * MVC tests for {@link RoadmapShareLinkController} (US22.3.5, authenticated management) — full
 * Spring context against a real PostgreSQL 18 (Testcontainers, required only to boot the
 * context/Flyway), with {@link RoadmapShareService} and {@link RoadmapEditPolicy} mocked via
 * {@code @MockitoBean} — mirrors {@code fr.pivot.pilotage.roadmap.RoadmapControllerIT}'s
 * established pattern. No actual share-link row is written here — that is
 * {@link RoadmapShareServiceIT}'s job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RoadmapShareLinkControllerIT {

    private static final long TENANT = 42L;
    private static final long TEAM = 5L;
    private static final long PROJECT = 100L;
    private static final String BASE_PATH =
            "/tenants/" + TENANT + "/teams/" + TEAM + "/projects/" + PROJECT + "/roadmap/share-links";

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
    private RoadmapShareService shareService;

    @MockitoBean
    private RoadmapEditPolicy editPolicy;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // -------- create ---------------------------------------------------------------------------

    @Test
    void createShareLink_authorized_returns201WithRawTokenAndInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(shareService.createShareLink(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateShareLinkRequest.class)))
                .thenReturn(new CreateShareLinkResponse(1L, "raw-token-value", Instant.now(), null));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.token").value("raw-token-value"));

        verify(shareService).createShareLink(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateShareLinkRequest.class));
    }

    @Test
    void createShareLink_notAuthorized_returns403AndNeverInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(shareService, never()).createShareLink(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void createShareLink_unknownProject_returns404() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(shareService.createShareLink(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateShareLinkRequest.class)))
                .thenThrow(new ProjectNotFoundException(PROJECT, TENANT, TEAM));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createShareLink_pastExpiry_returns400WithBody() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(shareService.createShareLink(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateShareLinkRequest.class)))
                .thenThrow(new InvalidShareLinkExpiryException("expiresAt must be strictly in the future"));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHARE_LINK_EXPIRY_INVALID"));
    }

    // -------- list -------------------------------------------------------------------------------

    @Test
    void listShareLinks_authorized_returnsServiceResultAsJson() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(shareService.listShareLinks(TENANT, TEAM, PROJECT))
                .thenReturn(List.of(new ShareLinkResponse(1L, Instant.now(), null, null, true)));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void listShareLinks_notAuthorized_returns403() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(get(BASE_PATH)).andExpect(status().isForbidden());

        verify(shareService, never()).listShareLinks(anyLong(), anyLong(), anyLong());
    }

    // -------- revoke -----------------------------------------------------------------------------

    @Test
    void revokeShareLink_authorized_returns204AndInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(delete(BASE_PATH + "/7")).andExpect(status().isNoContent());

        verify(shareService).revokeShareLink(TENANT, TEAM, PROJECT, 7L);
    }

    @Test
    void revokeShareLink_notAuthorized_returns403AndNeverInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(delete(BASE_PATH + "/7")).andExpect(status().isForbidden());

        verify(shareService, never()).revokeShareLink(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void revokeShareLink_unknownShareLink_returns404() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        org.mockito.Mockito.doThrow(new ShareLinkNotFoundException(999L, PROJECT))
                .when(shareService).revokeShareLink(TENANT, TEAM, PROJECT, 999L);

        mockMvc.perform(delete(BASE_PATH + "/999")).andExpect(status().isNotFound());
    }
}

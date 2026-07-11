package fr.pivot.pilotage.roadmap;

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

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link RoadmapShareViewController} (US22.3.5, public consultation) — full Spring
 * context against a real PostgreSQL 18 (Testcontainers, required only to boot the context/Flyway),
 * with {@link RoadmapShareService} mocked via {@code @MockitoBean} — mirrors
 * {@code fr.pivot.pilotage.roadmap.RoadmapControllerIT}'s established pattern.
 *
 * <p>Deliberately has <strong>no</strong> {@code @MockitoBean RoadmapEditPolicy} interaction —
 * this controller does not depend on it at all (public path, no edit gate), which this test class
 * itself demonstrates: every test succeeds without ever touching the policy mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RoadmapShareViewControllerIT {

    private static final String BASE_PATH = "/public/roadmap-shares";

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

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void viewSharedRoadmap_validToken_returns200WithRoadmapData() throws Exception {
        when(shareService.viewSharedRoadmap("valid-token")).thenReturn(new RoadmapShareViewResponse(
                "My Project", List.of(new LaneResponse(1L, "Theme A", 0)), List.of()));

        mockMvc.perform(get(BASE_PATH + "/valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("My Project"))
                .andExpect(jsonPath("$.lanes[0].name").value("Theme A"));
    }

    @Test
    void viewSharedRoadmap_invalidToken_returns404WithExplicitMessage() throws Exception {
        when(shareService.viewSharedRoadmap("bad-token")).thenThrow(new ShareLinkAccessDeniedException());

        mockMvc.perform(get(BASE_PATH + "/bad-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHARE_LINK_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
    }
}

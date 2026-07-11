package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.schedule.NodeKind;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link WbsTaskController} (US22.4.1a/b/c) — full Spring context against a real
 * PostgreSQL 18 (Testcontainers, only to boot the context/Flyway), with {@link WbsTaskService} and
 * {@link WbsEditPolicy} mocked via {@code @MockitoBean}. Mirrors {@code RoadmapControllerIT}. Covers
 * the HTTP contract: the 403 write gate, the 422 client-{@code wbsCode} refusal, the 404
 * non-disclosure, and the 200/201 delegation. Row-level behaviour is {@link WbsTaskServiceIT}'s job.
 *
 * <p>MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly, without the
 * {@code server.servlet.context-path} prefix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WbsTaskControllerIT {

    private static final long TENANT = 42L;
    private static final long TEAM = 5L;
    private static final long PROJECT = 100L;
    private static final long TASK = 7L;
    private static final String BASE = "/tenants/" + TENANT + "/teams/" + TEAM + "/projects/" + PROJECT + "/gantt";

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
    private WbsTaskService wbsTaskService;

    @MockitoBean
    private DependencyService dependencyService;

    @MockitoBean
    private WbsEditPolicy editPolicy;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private static WbsTaskResponse sampleNode() {
        return new WbsTaskResponse(TASK, null, "1", "Design", NodeKind.LEAF, 0, null, null, null,
                null, null, false, WbsTaskResponse.ARIA_ROLE_TREEITEM, 1, 1, 1, false, 0);
    }

    // -------- read: tree --------------------------------------------------------------------------

    @Test
    void tree_returnsServiceResultAsJson() throws Exception {
        when(wbsTaskService.tree(TENANT, TEAM, PROJECT))
                .thenReturn(WbsTreeResponse.of(PROJECT, List.of(sampleNode())));

        mockMvc.perform(get(BASE + "/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ariaRole").value("tree"))
                .andExpect(jsonPath("$.nodes[0].wbsCode").value("1"))
                .andExpect(jsonPath("$.nodes[0].ariaRole").value("treeitem"));
    }

    @Test
    void tree_unknownProject_returns404() throws Exception {
        when(wbsTaskService.tree(TENANT, TEAM, PROJECT))
                .thenThrow(new WbsProjectNotFoundException(PROJECT, TENANT, TEAM));

        mockMvc.perform(get(BASE + "/tree")).andExpect(status().isNotFound());
    }

    // -------- create: gated write -----------------------------------------------------------------

    @Test
    void createTask_authorized_returns201AndInvokesService() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(wbsTaskService.createTask(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateWbsTaskRequest.class)))
                .thenReturn(sampleNode());

        mockMvc.perform(post(BASE + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Design\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value(TASK));
    }

    @Test
    void createTask_unauthorized_returns403_andServiceNeverCalled() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Design\"}"))
                .andExpect(status().isForbidden());

        verify(wbsTaskService, never()).createTask(anyLong(), anyLong(), anyLong(),
                any(CreateWbsTaskRequest.class));
    }

    // -------- create Error: a client-supplied wbsCode is refused 422 (derived field) --------------

    @Test
    void createTask_withClientWbsCode_returns422() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(post(BASE + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Design\",\"wbsCode\":\"1.2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(DerivedFieldNotEditableException.CODE));

        verify(wbsTaskService, never()).createTask(anyLong(), anyLong(), anyLong(),
                any(CreateWbsTaskRequest.class));
    }

    // -------- indent / outdent: gated ------------------------------------------------------------

    @Test
    void indent_unauthorized_returns403() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);
        mockMvc.perform(patch(BASE + "/tasks/" + TASK + "/indent")).andExpect(status().isForbidden());
    }

    @Test
    void indent_firstTask_returns422() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(wbsTaskService.indent(TENANT, TEAM, PROJECT, TASK))
                .thenThrow(IllegalWbsMoveException.indentFirstTask(TASK));

        mockMvc.perform(patch(BASE + "/tasks/" + TASK + "/indent"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(IllegalWbsMoveException.CODE));
    }

    // -------- move Error: hierarchy cycle → 409 (decision D4) -------------------------------------

    @Test
    void move_hierarchyCycle_returns409() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(wbsTaskService.move(eq(TENANT), eq(TEAM), eq(PROJECT), eq(TASK), any(MoveWbsTaskRequest.class)))
                .thenThrow(new WbsHierarchyCycleException(TASK, 99L));

        mockMvc.perform(patch(BASE + "/tasks/" + TASK + "/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentTaskId\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WBS_HIERARCHY_CYCLE"));
    }

    // ============================ US22.4.3 — typed dependencies ==================================

    private static DependencyResponse fsDependency() {
        return new DependencyResponse(3L, 10L, 20L, fr.pivot.pilotage.schedule.DependencyLinkType.FS, 0);
    }

    // -------- read: list is not gated ------------------------------------------------------------

    @Test
    void listDependencies_read_returns200() throws Exception {
        when(dependencyService.list(TENANT, TEAM, PROJECT)).thenReturn(List.of(fsDependency()));

        mockMvc.perform(get(BASE + "/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].linkType").value("FS"));
    }

    // -------- create: FS default applied, delegates, 201 -----------------------------------------

    @Test
    void createDependency_authorized_defaultsToFs_returns201() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(dependencyService.create(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateDependencyRequest.class)))
                .thenReturn(fsDependency());

        // Body omits linkType — the record's canonical constructor must default it to FS.
        mockMvc.perform(post(BASE + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predecessorTaskId\":10,\"successorTaskId\":20}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkType").value("FS"));
    }

    // -------- Security AC: create write gated → 403, service never called ------------------------

    @Test
    void createDependency_unauthorized_returns403_andServiceNeverCalled() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predecessorTaskId\":10,\"successorTaskId\":20,\"linkType\":\"SS\"}"))
                .andExpect(status().isForbidden());

        verify(dependencyService, never()).create(anyLong(), anyLong(), anyLong(),
                any(CreateDependencyRequest.class));
    }

    // -------- Error AC: self-dependency → 422 ----------------------------------------------------

    @Test
    void createDependency_selfLink_returns422() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(dependencyService.create(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateDependencyRequest.class)))
                .thenThrow(InvalidDependencyException.selfDependency(10L));

        mockMvc.perform(post(BASE + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predecessorTaskId\":10,\"successorTaskId\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(InvalidDependencyException.CODE));
    }

    // -------- Error AC: duplicate link → 409 -----------------------------------------------------

    @Test
    void createDependency_duplicate_returns409() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(dependencyService.create(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateDependencyRequest.class)))
                .thenThrow(new DuplicateDependencyException(10L, 20L,
                        fr.pivot.pilotage.schedule.DependencyLinkType.FS));

        mockMvc.perform(post(BASE + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predecessorTaskId\":10,\"successorTaskId\":20}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DuplicateDependencyException.CODE));
    }

    // -------- Error AC: cycle → 409 SCHEDULE_CYCLE (distinct from WBS hierarchy cycle) ------------

    @Test
    void createDependency_cycle_returns409_scheduleCycle() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(dependencyService.create(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateDependencyRequest.class)))
                .thenThrow(new DependencyCycleException("dependency cycle detected"));

        mockMvc.perform(post(BASE + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predecessorTaskId\":20,\"successorTaskId\":10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_CYCLE"));
    }

    // -------- Security AC: cross-tenant / unknown endpoint → 404 non-disclosure -------------------

    @Test
    void createDependency_unknownEndpoint_returns404() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(dependencyService.create(eq(TENANT), eq(TEAM), eq(PROJECT), any(CreateDependencyRequest.class)))
                .thenThrow(DependencyNotFoundException.endpointTask(999L, PROJECT));

        mockMvc.perform(post(BASE + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predecessorTaskId\":999,\"successorTaskId\":20}"))
                .andExpect(status().isNotFound());
    }

    // -------- update: gated, delegates -----------------------------------------------------------

    @Test
    void updateDependency_authorized_returns200() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(dependencyService.update(eq(TENANT), eq(TEAM), eq(PROJECT), eq(3L),
                any(UpdateDependencyRequest.class)))
                .thenReturn(new DependencyResponse(3L, 10L, 20L,
                        fr.pivot.pilotage.schedule.DependencyLinkType.SS, 120));

        mockMvc.perform(put(BASE + "/dependencies/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkType\":\"SS\",\"lagMinutes\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkType").value("SS"))
                .andExpect(jsonPath("$.lagMinutes").value(120));
    }

    // -------- delete: gated → 204 ----------------------------------------------------------------

    @Test
    void deleteDependency_authorized_returns204() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(delete(BASE + "/dependencies/3")).andExpect(status().isNoContent());

        verify(dependencyService).delete(TENANT, TEAM, PROJECT, 3L);
    }

    @Test
    void deleteDependency_unauthorized_returns403() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(delete(BASE + "/dependencies/3")).andExpect(status().isForbidden());

        verify(dependencyService, never()).delete(anyLong(), anyLong(), anyLong(), anyLong());
    }
}

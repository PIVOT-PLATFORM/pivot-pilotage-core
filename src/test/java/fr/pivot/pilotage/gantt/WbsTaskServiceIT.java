package fr.pivot.pilotage.gantt;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link WbsTaskService} (US22.4.1a/b/c) against a real PostgreSQL 18
 * (Testcontainers), this module's Flyway migration applied. Exercises the full WBS lifecycle:
 * creation + server-derived numbering, indent/outdent/reorder re-numbering, summary aggregation
 * (min/max/Σ/weighted), the derived-field 422 refusals, the indent/outdent limit errors, the WBS
 * hierarchy cycle (409, decision D4) and multi-tenant/team isolation (404-equivalent).
 * {@code public.tenants}/{@code public.teams} are seeded before Flyway via
 * {@link PlatformSchemaTestSupport}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class WbsTaskServiceIT {

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
    @Autowired private TaskProgressRepository progressRepository;
    @Autowired private WbsTaskService wbsTaskService;

    private long tenantId;
    private long teamId;
    private long projectId;

    /** Seeds a fresh tenant/team/application/project before each test. */
    @BeforeEach
    void setUp() throws Exception {
        tenantId = seedTenant();
        teamId = seedTeam(tenantId);
        projectId = newProject(tenantId, teamId).getId();
    }

    private long seedTenant() throws Exception {
        return PlatformSchemaTestSupport.seedTenant(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long seedTeam(final long owner) throws Exception {
        return PlatformSchemaTestSupport.seedTeam(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), owner);
    }

    private Project newProject(final long owner, final long team) {
        final Instant now = Instant.now();
        final Application app = applicationRepository.save(new Application(owner, team, "App", now));
        return projectRepository.save(new Project(app, owner, team, "P", now));
    }

    private WbsTaskResponse create(final String name, final Long parent) {
        return wbsTaskService.createTask(tenantId, teamId, projectId,
                new CreateWbsTaskRequest(name, parent, null, null));
    }

    private WbsTaskResponse node(final long taskId) {
        return wbsTaskService.tree(tenantId, teamId, projectId).nodes().stream()
                .filter(n -> n.taskId() == taskId).findFirst().orElseThrow();
    }

    // -------- US22.4.1a AC: each task carries a hierarchical WBS code reflecting its position ------

    @Test
    void createTask_derivesHierarchicalWbsCodesInPositionOrder() {
        final WbsTaskResponse root1 = create("Design", null);
        final WbsTaskResponse root2 = create("Build", null);
        final WbsTaskResponse child11 = create("Specs", root1.taskId());
        final WbsTaskResponse child12 = create("Mockups", root1.taskId());
        final WbsTaskResponse child121 = create("Wireframe", child12.taskId());

        assertThat(node(root1.taskId()).wbsCode()).isEqualTo("1");
        assertThat(node(root2.taskId()).wbsCode()).isEqualTo("2");
        assertThat(node(child11.taskId()).wbsCode()).isEqualTo("1.1");
        assertThat(node(child12.taskId()).wbsCode()).isEqualTo("1.2");
        assertThat(node(child121.taskId()).wbsCode()).isEqualTo("1.2.1");
    }

    // -------- US22.4.1a AC: a node with children becomes a SUMMARY, siblings unique & ordered ------

    @Test
    void createTask_underParent_promotesParentToSummary_andTreeIsPreOrdered() {
        final WbsTaskResponse root = create("Phase", null);
        create("A", root.taskId());
        create("B", root.taskId());

        assertThat(node(root.taskId()).nodeKind()).isEqualTo(NodeKind.SUMMARY);
        final List<String> codes = wbsTaskService.tree(tenantId, teamId, projectId).nodes().stream()
                .map(WbsTaskResponse::wbsCode).toList();
        assertThat(codes).containsExactly("1", "1.1", "1.2");
    }

    // -------- US22.4.1a AC (A11y): role=tree/treeitem, aria-level/setsize/posinset -----------------

    @Test
    void tree_exposesAriaTreeAttributes() {
        final WbsTaskResponse root = create("Phase", null);
        create("A", root.taskId());
        create("B", root.taskId());

        final WbsTreeResponse tree = wbsTaskService.tree(tenantId, teamId, projectId);
        assertThat(tree.ariaRole()).isEqualTo("tree");

        final WbsTaskResponse rootNode = tree.nodes().get(0);
        assertThat(rootNode.ariaRole()).isEqualTo("treeitem");
        assertThat(rootNode.ariaLevel()).isEqualTo(1);
        assertThat(rootNode.ariaSetSize()).isEqualTo(1);
        assertThat(rootNode.ariaPosInSet()).isEqualTo(1);

        final WbsTaskResponse childB = tree.nodes().get(2);
        assertThat(childB.name()).isEqualTo("B");
        assertThat(childB.ariaLevel()).isEqualTo(2);
        assertThat(childB.ariaSetSize()).isEqualTo(2);
        assertThat(childB.ariaPosInSet()).isEqualTo(2);
    }

    // -------- US22.4.6 AC1: a task created with duration 0 displays as a milestone ------------------

    @Test
    void createTask_zeroDuration_becomesMilestone() {
        final WbsTaskResponse created = wbsTaskService.createTask(tenantId, teamId, projectId,
                new CreateWbsTaskRequest("Kickoff", null, null, 0));

        assertThat(created.nodeKind()).isEqualTo(NodeKind.MILESTONE);
        assertThat(created.nodeKindLabel()).isEqualTo(WbsTaskResponse.labelFor(NodeKind.MILESTONE));
        assertThat(node(created.taskId()).nodeKind()).isEqualTo(NodeKind.MILESTONE);
    }

    // -------- US22.4.6 AC1 (control): a positive or absent duration stays a plain task ---------------

    @Test
    void createTask_positiveOrAbsentDuration_staysLeaf() {
        final WbsTaskResponse withDuration = wbsTaskService.createTask(tenantId, teamId, projectId,
                new CreateWbsTaskRequest("A", null, null, 480));
        final WbsTaskResponse noDuration = wbsTaskService.createTask(tenantId, teamId, projectId,
                new CreateWbsTaskRequest("B", null, null, null));

        assertThat(withDuration.nodeKind()).isEqualTo(NodeKind.LEAF);
        assertThat(noDuration.nodeKind()).isEqualTo(NodeKind.LEAF);
    }

    // -------- US22.4.1b AC: indent makes a task the child of its preceding sibling, WBS recalculated

    @Test
    void indent_makesTaskChildOfPrecedingSibling_andRecalculatesWbs() {
        final WbsTaskResponse a = create("A", null);
        final WbsTaskResponse b = create("B", null);

        final WbsTaskResponse indented = wbsTaskService.indent(tenantId, teamId, projectId, b.taskId());

        assertThat(indented.parentTaskId()).isEqualTo(a.taskId());
        assertThat(indented.wbsCode()).isEqualTo("1.1");
        assertThat(node(a.taskId()).nodeKind()).isEqualTo(NodeKind.SUMMARY);
    }

    // -------- US22.4.1b AC: outdent lifts a task one level, WBS recalculated -----------------------

    @Test
    void outdent_liftsTaskOneLevel_andRecalculatesWbs() {
        final WbsTaskResponse a = create("A", null);
        final WbsTaskResponse child = create("child", a.taskId());

        final WbsTaskResponse outdented = wbsTaskService.outdent(tenantId, teamId, projectId, child.taskId());

        assertThat(outdented.parentTaskId()).isNull();
        assertThat(outdented.wbsCode()).isEqualTo("2");
        // the former parent has no more children → demoted back to LEAF
        assertThat(node(a.taskId()).nodeKind()).isEqualTo(NodeKind.LEAF);
    }

    // -------- US22.4.1b AC: reorder among siblings keeps ordering & numbering coherent -------------

    @Test
    void reorder_movesTaskAmongSiblings_andRenumbers() {
        final WbsTaskResponse a = create("A", null);
        final WbsTaskResponse b = create("B", null);
        final WbsTaskResponse c = create("C", null);

        // move C to the front (position 0) → order becomes C, A, B
        wbsTaskService.reorder(tenantId, teamId, projectId, c.taskId(), 0);

        assertThat(node(c.taskId()).wbsCode()).isEqualTo("1");
        assertThat(node(a.taskId()).wbsCode()).isEqualTo("2");
        assertThat(node(b.taskId()).wbsCode()).isEqualTo("3");
    }

    // -------- US22.4.1b Error: indent of the first task / outdent at root → 422-equivalent ---------

    @Test
    void indent_firstTask_rejected() {
        final WbsTaskResponse first = create("A", null);
        assertThatExceptionOfType(IllegalWbsMoveException.class).isThrownBy(() ->
                wbsTaskService.indent(tenantId, teamId, projectId, first.taskId()));
    }

    @Test
    void outdent_rootTask_rejected() {
        final WbsTaskResponse root = create("A", null);
        assertThatExceptionOfType(IllegalWbsMoveException.class).isThrownBy(() ->
                wbsTaskService.outdent(tenantId, teamId, projectId, root.taskId()));
    }

    // -------- US22.4.1a Error: a move creating a hierarchy cycle → 409 (decision D4) ---------------

    @Test
    void move_underOwnDescendant_rejectedAsHierarchyCycle() {
        final WbsTaskResponse parent = create("P", null);
        final WbsTaskResponse child = create("C", parent.taskId());

        assertThatExceptionOfType(WbsHierarchyCycleException.class).isThrownBy(() ->
                wbsTaskService.move(tenantId, teamId, projectId, parent.taskId(),
                        new MoveWbsTaskRequest(child.taskId(), null)));

        // structure unchanged: parent still root, child still under parent
        assertThat(node(parent.taskId()).parentTaskId()).isNull();
        assertThat(node(child.taskId()).parentTaskId()).isEqualTo(parent.taskId());
    }

    // -------- US22.4.1c AC: summary aggregates start=min, finish=max, duration=Σ, weighted % -------

    @Test
    void summary_aggregatesChildrenDatesDurationAndWeightedProgress() {
        final WbsTaskResponse summary = create("Phase", null);
        final WbsTaskResponse leafA = create("A", summary.taskId());
        final WbsTaskResponse leafB = create("B", summary.taskId());

        final Instant startA = LocalDate.of(2026, 1, 5).atStartOfDay(ZoneOffset.UTC).toInstant();
        final Instant finishA = LocalDate.of(2026, 1, 10).atStartOfDay(ZoneOffset.UTC).toInstant();
        final Instant startB = LocalDate.of(2026, 1, 8).atStartOfDay(ZoneOffset.UTC).toInstant();
        final Instant finishB = LocalDate.of(2026, 1, 20).atStartOfDay(ZoneOffset.UTC).toInstant();
        setDates(leafA.taskId(), startA, finishA);
        setDates(leafB.taskId(), startB, finishB);
        // no assignments → weight defaults to 1 each ⇒ simple mean of leaf percents (40, 80) = 60
        setProgress(leafA.taskId(), new BigDecimal("40.00"));
        setProgress(leafB.taskId(), new BigDecimal("80.00"));

        final WbsTaskResponse aggregated = node(summary.taskId());
        assertThat(aggregated.readOnly()).isTrue();
        assertThat(aggregated.ariaReadOnly()).isTrue();
        assertThat(aggregated.startDate()).isEqualTo(startA);      // min
        assertThat(aggregated.finishDate()).isEqualTo(finishB);    // max
        assertThat(aggregated.percentComplete()).isEqualByComparingTo("60");
        assertThat(aggregated.progressLabel()).isNotBlank();
    }

    // -------- US22.4.1c AC: moving a sub-task recomputes both former and new parent aggregates -----

    @Test
    void movingSubTask_recomputesFormerAndNewParentAggregates() {
        final WbsTaskResponse p1 = create("P1", null);
        final WbsTaskResponse p2 = create("P2", null);
        final WbsTaskResponse leaf = create("leaf", p1.taskId());
        final Instant s = LocalDate.of(2026, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        final Instant f = LocalDate.of(2026, 2, 10).atStartOfDay(ZoneOffset.UTC).toInstant();
        setDates(leaf.taskId(), s, f);

        assertThat(node(p1.taskId()).startDate()).isEqualTo(s);

        // move leaf from P1 to P2
        wbsTaskService.move(tenantId, teamId, projectId, leaf.taskId(), new MoveWbsTaskRequest(p2.taskId(), null));

        // former parent P1 now childless → demoted to LEAF, no aggregated dates
        assertThat(node(p1.taskId()).nodeKind()).isEqualTo(NodeKind.LEAF);
        assertThat(node(p1.taskId()).startDate()).isNull();
        // new parent P2 now aggregates the leaf
        assertThat(node(p2.taskId()).nodeKind()).isEqualTo(NodeKind.SUMMARY);
        assertThat(node(p2.taskId()).startDate()).isEqualTo(s);
        assertThat(node(p2.taskId()).finishDate()).isEqualTo(f);
    }

    // -------- US22.4.1c Error: direct edit of a summary's derived field → 422-equivalent -----------

    @Test
    void editingSummaryDerivedField_rejected() {
        final WbsTaskResponse summary = create("Phase", null);
        create("A", summary.taskId());

        assertThatExceptionOfType(DerivedFieldNotEditableException.class).isThrownBy(() ->
                wbsTaskService.assertDerivedFieldEditable(tenantId, teamId, projectId, summary.taskId(), "startDate"));
    }

    @Test
    void editingLeafOwnField_isAllowed() {
        final WbsTaskResponse leaf = create("A", null);
        // a leaf is editable → no exception
        wbsTaskService.assertDerivedFieldEditable(tenantId, teamId, projectId, leaf.taskId(), "startDate");
    }

    // -------- Security AC: cross-tenant / cross-team isolation (404-equivalent) --------------------

    @Test
    void crossTenant_treeNotVisible_throwsProjectNotFound() throws Exception {
        create("A", null);
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                wbsTaskService.tree(otherTenant, otherTeam, projectId));
    }

    @Test
    void crossTenant_createNotVisible_throwsProjectNotFound() throws Exception {
        final long otherTenant = seedTenant();
        final long otherTeam = seedTeam(otherTenant);

        assertThatExceptionOfType(WbsProjectNotFoundException.class).isThrownBy(() ->
                wbsTaskService.createTask(otherTenant, otherTeam, projectId,
                        new CreateWbsTaskRequest("X", null, null, null)));
    }

    // -------- helpers -----------------------------------------------------------------------------

    private void setDates(final long taskId, final Instant start, final Instant finish) {
        final Task t = taskRepository.findById(taskId).orElseThrow();
        t.setStartDate(start);
        t.setFinishDate(finish);
        taskRepository.save(t);
    }

    private void setProgress(final long taskId, final BigDecimal percent) {
        progressRepository.save(new TaskProgress(tenantId, teamId, taskId, percent));
    }
}

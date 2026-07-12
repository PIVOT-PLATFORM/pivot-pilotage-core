package fr.pivot.pilotage.gantt;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the WBS (Work Breakdown Structure) of a project's detailed Gantt
 * (F22.4 — US22.4.1a modèle arborescent &amp; numérotation, US22.4.1b indent/outdent &amp;
 * réordonnancement, US22.4.1c agrégation des tâches récapitulatives). Thin by design (CLAUDE.md
 * §Standards): the role gate is delegated to {@link WbsEditPolicy} and every operation to
 * {@link WbsTaskService}; errors are mapped by {@link WbsExceptionHandler}.
 *
 * <p><strong>URL shape — gap-era, mirrors {@code RoadmapController}.</strong>
 * {@code pivot-core-starter} (TenantContext) is not published (CLAUDE.md §gap, TODO-SETUP §5), so
 * {@code tenantId}/{@code teamId} are explicit path variables here too — never a body/query param.
 * Once the starter is consumable they move to the security context with no change to
 * {@link WbsTaskService}. Prefix is consistent with roadmap:
 * {@code /tenants/{tenantId}/teams/{teamId}/projects/{projectId}/gantt}.
 *
 * <p><strong>Contract</strong>
 * <ul>
 *   <li>{@code GET  .../gantt/tree} — read the ordered WBS tree (role="tree" + treeitems).</li>
 *   <li>{@code POST .../gantt/tasks} — create a task, optionally under a parent (write, gated). A
 *       body carrying {@code wbsCode} is rejected {@code 422} (derived field).</li>
 *   <li>{@code PATCH .../gantt/tasks/{taskId}/indent} — indent (write, gated).</li>
 *   <li>{@code PATCH .../gantt/tasks/{taskId}/outdent} — outdent (write, gated).</li>
 *   <li>{@code PATCH .../gantt/tasks/{taskId}/move} — combined reparent/reorder (write, gated).</li>
 *   <li>{@code GET    .../gantt/dependencies} — list the project's typed dependencies (US22.4.3).</li>
 *   <li>{@code POST   .../gantt/dependencies} — create a typed link (write, gated). A cycle is
 *       rejected {@code 409 SCHEDULE_CYCLE}, a duplicate {@code 409}, a self-link {@code 422}.</li>
 *   <li>{@code PUT    .../gantt/dependencies/{dependencyId}} — retype/relag a link (write, gated).</li>
 *   <li>{@code DELETE .../gantt/dependencies/{dependencyId}} — remove a link (write, gated).</li>
 *   <li>{@code PATCH  .../gantt/tasks/{taskId}/duration} — set the duration (US22.4.2, write, gated).
 *       A negative or non-milestone-zero value is rejected {@code 422}.</li>
 *   <li>{@code PATCH  .../gantt/tasks/{taskId}/effort} — set an assignment's units, re-deriving
 *       work = duration × units (US22.4.2, write, gated). Non-positive units → {@code 422}.</li>
 *   <li>{@code PATCH  .../gantt/tasks/{taskId}/scheduling-mode} — toggle AUTO/MANUAL, exposing the
 *       manual variance (US22.4.2, write, gated).</li>
 *   <li>{@code GET   .../gantt/tasks/{taskId}/constraint} — read a task's constraint/deadline and the
 *       engine's current warnings about it (US22.4.4, read — not gated, only isolation-checked, so a
 *       raised conflict stays visible to every role).</li>
 *   <li>{@code PUT   .../gantt/tasks/{taskId}/constraint} — set (create or replace) a task's
 *       constraint/deadline and re-run the CPM (US22.4.4, write, gated). A date-bearing type submitted
 *       without a {@code constraintDate} is rejected {@code 422}.</li>
 *   <li>{@code POST   .../gantt/tasks/recurring} — create a periodic task series and materialise its
 *       occurrences (US22.4.6, write, gated). A task created via {@code POST .../gantt/tasks} with
 *       {@code durationMinutes=0} is auto-classified a milestone (US22.4.6 AC1) — no dedicated
 *       endpoint, the existing create path already covers it. A missing/invalid frequency or
 *       occurrence count is rejected {@code 422}.</li>
 *   <li>{@code PATCH  .../gantt/tasks/{taskId}/progress} — record percent complete, physical
 *       percent, actual start/finish and this entry's status date (US22.4.8, write, gated); the
 *       bar and the actual/remaining work update together. Out-of-range percent or an actual
 *       finish before the actual start is rejected {@code 422}. A summary task (aggregated,
 *       read-only) is also {@code 422}. The progress-line data (expected percent / late flag at
 *       the project's status date) is exposed per node by {@code GET .../gantt/tree}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/tenants/{tenantId}/teams/{teamId}/projects/{projectId}/gantt")
public class WbsTaskController {

    private final WbsTaskService wbsTaskService;
    private final DependencyService dependencyService;
    private final TaskEffortService taskEffortService;
    private final TaskConstraintService taskConstraintService;
    private final RecurringTaskService recurringTaskService;
    private final TaskProgressService taskProgressService;
    private final WbsEditPolicy editPolicy;

    /**
     * Constructs the controller.
     *
     * @param wbsTaskService        the WBS business logic
     * @param dependencyService     the typed-dependency business logic (US22.4.3)
     * @param taskEffortService     the duration/effort/scheduling-mode business logic (US22.4.2)
     * @param taskConstraintService the constraint/deadline business logic (US22.4.4)
     * @param recurringTaskService  the periodic-task series/occurrences business logic (US22.4.6)
     * @param taskProgressService   the progress-tracking business logic (US22.4.8)
     * @param editPolicy            the role-gate extension point for writes (deny-all until the
     *                              starter publishes membership, mirrors {@code RoadmapEditPolicy})
     */
    public WbsTaskController(final WbsTaskService wbsTaskService, final DependencyService dependencyService,
            final TaskEffortService taskEffortService, final TaskConstraintService taskConstraintService,
            final RecurringTaskService recurringTaskService, final TaskProgressService taskProgressService,
            final WbsEditPolicy editPolicy) {
        this.wbsTaskService = wbsTaskService;
        this.dependencyService = dependencyService;
        this.taskEffortService = taskEffortService;
        this.taskConstraintService = taskConstraintService;
        this.recurringTaskService = recurringTaskService;
        this.taskProgressService = taskProgressService;
        this.editPolicy = editPolicy;
    }

    /**
     * Reads a project's ordered WBS tree.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the ordered tree; {@code 404} if the project is not visible
     */
    @GetMapping("/tree")
    public ResponseEntity<WbsTreeResponse> tree(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(wbsTaskService.tree(tenantId, teamId, projectId));
    }

    /**
     * Creates a task in the project's WBS, optionally under a parent. The WBS code is derived
     * server-side; a client-supplied {@code wbsCode} is rejected {@code 422}.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the creation payload
     * @return {@code 201 Created} with the created (numbered) task; {@code 403} if unauthorized;
     *         {@code 404} if the project or the parent is not visible; {@code 422} if a derived
     *         field is supplied
     */
    @PostMapping("/tasks")
    public ResponseEntity<WbsTaskResponse> createTask(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @Valid @RequestBody final CreateWbsTaskRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wbsTaskService.createTask(tenantId, teamId, projectId, request));
    }

    /**
     * Indents a task (it becomes a sub-task of its preceding sibling); the WBS is re-derived.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to indent
     * @return {@code 200 OK} with the re-numbered task; {@code 403} if unauthorized; {@code 404} if
     *         not visible; {@code 422} if the task is the first of the plan (no possible parent)
     */
    @PatchMapping("/tasks/{taskId}/indent")
    public ResponseEntity<WbsTaskResponse> indent(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId) {
        requireEditAuthorized();
        return ResponseEntity.ok(wbsTaskService.indent(tenantId, teamId, projectId, taskId));
    }

    /**
     * Outdents a task (it rises one level); the WBS is re-derived.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to outdent
     * @return {@code 200 OK} with the re-numbered task; {@code 403} if unauthorized; {@code 404} if
     *         not visible; {@code 422} if the task is already at the WBS root
     */
    @PatchMapping("/tasks/{taskId}/outdent")
    public ResponseEntity<WbsTaskResponse> outdent(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId) {
        requireEditAuthorized();
        return ResponseEntity.ok(wbsTaskService.outdent(tenantId, teamId, projectId, taskId));
    }

    /**
     * Moves a task: reparent (including to the WBS root via {@link MoveWbsTaskRequest#ROOT}) and/or
     * reorder among its siblings; the WBS is re-derived.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to move
     * @param request   the move payload
     * @return {@code 200 OK} with the re-numbered task; {@code 403} if unauthorized; {@code 404} if
     *         the task or a supplied parent is not visible; {@code 409} if the move creates a
     *         hierarchy cycle (decision D4)
     */
    @PatchMapping("/tasks/{taskId}/move")
    public ResponseEntity<WbsTaskResponse> move(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId, @RequestBody final MoveWbsTaskRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(wbsTaskService.move(tenantId, teamId, projectId, taskId, request));
    }

    /**
     * Lists the project's typed dependencies (US22.4.3). A read — not gated by the edit policy, only
     * by tenant/team/project isolation.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the dependencies; {@code 404} if the project is not visible
     */
    @GetMapping("/dependencies")
    public ResponseEntity<List<DependencyResponse>> listDependencies(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(dependencyService.list(tenantId, teamId, projectId));
    }

    /**
     * Creates a typed dependency (FS/SS/FF/SF + signed lag) between two tasks of the project and
     * re-runs the CPM; a cycle is rejected atomically.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the creation payload (link type defaults to FS)
     * @return {@code 201 Created} with the created dependency; {@code 403} if unauthorized;
     *         {@code 404} if the project or an endpoint task is not visible; {@code 422} on a
     *         self-link; {@code 409} on a duplicate or on a cycle ({@code SCHEDULE_CYCLE})
     */
    @PostMapping("/dependencies")
    public ResponseEntity<DependencyResponse> createDependency(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @Valid @RequestBody final CreateDependencyRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dependencyService.create(tenantId, teamId, projectId, request));
    }

    /**
     * Retypes and/or relags an existing dependency and re-runs the CPM; a cycle is rejected
     * atomically.
     *
     * @param tenantId     the tenant's {@code public.tenants.id}
     * @param teamId       the team's {@code public.teams.id}
     * @param projectId    the project id
     * @param dependencyId the dependency id
     * @param request      the update payload
     * @return {@code 200 OK} with the updated dependency; {@code 403} if unauthorized; {@code 404} if
     *         the project or the dependency is not visible; {@code 409} on a duplicate retype or on a
     *         cycle ({@code SCHEDULE_CYCLE})
     */
    @PutMapping("/dependencies/{dependencyId}")
    public ResponseEntity<DependencyResponse> updateDependency(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long dependencyId, @Valid @RequestBody final UpdateDependencyRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(dependencyService.update(tenantId, teamId, projectId, dependencyId, request));
    }

    /**
     * Deletes a dependency and re-runs the CPM (removing an edge can never create a cycle).
     *
     * @param tenantId     the tenant's {@code public.tenants.id}
     * @param teamId       the team's {@code public.teams.id}
     * @param projectId    the project id
     * @param dependencyId the dependency id
     * @return {@code 204 No Content}; {@code 403} if unauthorized; {@code 404} if not visible
     */
    @DeleteMapping("/dependencies/{dependencyId}")
    public ResponseEntity<Void> deleteDependency(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long dependencyId) {
        requireEditAuthorized();
        dependencyService.delete(tenantId, teamId, projectId, dependencyId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets a task's duration in worked minutes and re-runs the CPM (US22.4.2, write, gated).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to edit
     * @param request   the duration payload
     * @return {@code 200 OK} with the refreshed scheduling state; {@code 403} if unauthorized;
     *         {@code 404} if not visible; {@code 422} if the duration is negative, zero on a
     *         non-milestone, or if a derived engine field is supplied
     */
    @PatchMapping("/tasks/{taskId}/duration")
    public ResponseEntity<TaskSchedulingResponse> setDuration(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId, @Valid @RequestBody final UpdateTaskDurationRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(taskEffortService.setDuration(tenantId, teamId, projectId, taskId,
                request.durationMinutes()));
    }

    /**
     * Sets a task assignment's resource units, re-deriving work = duration × units, and re-runs the
     * CPM (US22.4.2, write, gated).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to edit
     * @param request   the effort payload (resource reference + units percent)
     * @return {@code 200 OK} with the refreshed scheduling state (its {@code workMinutes} reflecting
     *         the relation); {@code 403} if unauthorized; {@code 404} if not visible; {@code 422} if
     *         the units are non-positive or if a derived engine field is supplied
     */
    @PatchMapping("/tasks/{taskId}/effort")
    public ResponseEntity<TaskSchedulingResponse> setEffort(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId, @Valid @RequestBody final UpdateTaskEffortRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(taskEffortService.setEffort(tenantId, teamId, projectId, taskId,
                request.resourceRef(), request.unitsPercent()));
    }

    /**
     * Switches a task between AUTO and MANUAL scheduling and re-runs the CPM (US22.4.2, write,
     * gated). The response exposes the manual variance ({@code plannedManual}/{@code wouldBeAuto}/
     * {@code deltaMinutes}) when the task is MANUAL.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to edit
     * @param request   the mode payload
     * @return {@code 200 OK} with the refreshed scheduling state (with the variance when MANUAL);
     *         {@code 403} if unauthorized; {@code 404} if not visible
     */
    @PatchMapping("/tasks/{taskId}/scheduling-mode")
    public ResponseEntity<TaskSchedulingResponse> setSchedulingMode(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId, @Valid @RequestBody final UpdateSchedulingModeRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(taskEffortService.setSchedulingMode(tenantId, teamId, projectId, taskId,
                request.schedulingMode()));
    }

    /**
     * Reads a task's constraint/deadline and the engine's current warnings about it (US22.4.4). Not
     * gated by the edit policy — only by tenant/team/project/task isolation — so a conflict raised by
     * an editor stays visible read-only to every other role (Security AC).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to read
     * @return {@code 200 OK} with the constraint/deadline and current warnings; {@code 404} if not
     *         visible
     */
    @GetMapping("/tasks/{taskId}/constraint")
    public ResponseEntity<TaskConstraintResponse> getConstraint(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId) {
        return ResponseEntity.ok(taskConstraintService.get(tenantId, teamId, projectId, taskId));
    }

    /**
     * Sets (creates or replaces) a task's constraint/deadline and re-runs the CPM (US22.4.4, write,
     * gated).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to constrain
     * @param request   the constraint/deadline payload
     * @return {@code 200 OK} with the persisted constraint/deadline and the fresh warnings for this
     *         task; {@code 403} if unauthorized; {@code 404} if not visible; {@code 422} if the type
     *         requires a date and none was supplied
     */
    @PutMapping("/tasks/{taskId}/constraint")
    public ResponseEntity<TaskConstraintResponse> setConstraint(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId, @Valid @RequestBody final UpsertTaskConstraintRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(taskConstraintService.upsert(tenantId, teamId, projectId, taskId, request));
    }

    /**
     * Creates a periodic task series and materialises its occurrences (US22.4.6, write, gated). The
     * occurrences respect the working calendar (US22.4.5) and, when {@code durationMinutes} is
     * {@code 0}/absent, are rendered as milestones (the same auto-classification as
     * {@link #createTask}).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the creation payload
     * @return {@code 201 Created} with the series and its generated occurrences; {@code 403} if
     *         unauthorized; {@code 404} if the project or the parent is not visible; {@code 422} if
     *         the frequency/occurrence count is missing or invalid, or exceeds the generation cap
     */
    @PostMapping("/tasks/recurring")
    public ResponseEntity<RecurringTaskResponse> createRecurringTask(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @Valid @RequestBody final CreateRecurringTaskRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(recurringTaskService.createRecurringTask(tenantId, teamId, projectId, request));
    }

    /**
     * Sets a task's progress (percent complete, optional physical percent, actual start/finish and
     * this entry's status/freshness date) and recomputes the actual/remaining work of its
     * assignments (US22.4.8, write, gated). The service appends the audit trail entry (author,
     * date — security AC).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param taskId    the task to edit
     * @param request   the progress payload
     * @return {@code 200 OK} with the refreshed progress state; {@code 403} if unauthorized;
     *         {@code 404} if not visible; {@code 422} if the percent is out of {@code [0, 100]},
     *         the actual finish precedes the actual start, the task is a summary (derived,
     *         read-only), or a derived field ({@code actualWorkMinutes}/{@code remainingWorkMinutes})
     *         is supplied
     */
    @PatchMapping("/tasks/{taskId}/progress")
    public ResponseEntity<TaskProgressResponse> setProgress(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long taskId, @Valid @RequestBody final UpdateTaskProgressRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(taskProgressService.setProgress(tenantId, teamId, projectId, taskId, request));
    }

    /**
     * Short-circuits every write endpoint before any service call when the caller is not authorized
     * (security AC — fail-closed today, see {@link DenyAllWbsEditPolicy}).
     *
     * @throws WbsEditForbiddenException if the current caller is not authorized
     */
    private void requireEditAuthorized() {
        if (!editPolicy.isAuthorized()) {
            throw new WbsEditForbiddenException();
        }
    }
}

package fr.pivot.pilotage.roadmap;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the roadmap-rapide contract (US22.3.1 — "Créer une roadmap rapide"):
 * posing initiatives on lanes without precise dates or child tasks, and moving/resizing them.
 *
 * <p><strong>URL shape — gap-era, mirrors {@code OrganizationProfileController}
 * (EN18.10 écart #3).</strong> {@code pivot-core-starter} (the module exposing
 * {@code TenantContext}) is not published (CLAUDE.md §gap, {@code TODO-SETUP.md} §5), so
 * {@code tenantId} and {@code teamId} are explicit path variables here too — never a body/query
 * param (CLAUDE.md §Isolation tenant forbids the latter two). Unlike
 * {@code OrganizationProfileController} (tenant-only resource), every roadmap-rapide resource also
 * needs a {@code teamId} boundary (per-team portfolio scoping, team_id retrofit) and a
 * {@code projectId} path segment identifying which project's roadmap is being edited — hence the
 * three-segment prefix {@code /tenants/{tenantId}/teams/{teamId}/projects/{projectId}/roadmap}.
 * Once {@code pivot-core-starter} is consumable, {@code tenantId}/{@code teamId} are expected to
 * move from path variables to the security context, with no change to {@link RoadmapService}.
 *
 * <p>No business logic here (CLAUDE.md §Standards) — the role gate is delegated to
 * {@link RoadmapEditPolicy} and every mutation to {@link RoadmapService}; both are exception-mapped
 * by {@link RoadmapExceptionHandler}.
 *
 * <p><strong>Full contract</strong> (see {@code pivot-docs}
 * {@code docs/backlog/EPIC-roadmap/FEATURES/roadmap-rapide/us-creer-roadmap-rapide.md}, section
 * "Notes d'implémentation", for the authoritative version consumed by the frontend):
 * <ul>
 *   <li>{@code GET  .../roadmap/lanes} — list a project's lanes, ordered by position.</li>
 *   <li>{@code POST .../roadmap/lanes} — create a lane (write, gated).</li>
 *   <li>{@code GET  .../roadmap/initiatives} — list a project's initiatives, ordered by lane then
 *       position.</li>
 *   <li>{@code POST .../roadmap/initiatives} — create an initiative on a lane (write, gated).</li>
 *   <li>{@code PATCH .../roadmap/initiatives/{initiativeId}} — move/resize/re-lane an initiative
 *       (write, gated).</li>
 *   <li>{@code PATCH .../roadmap/initiatives/{initiativeId}/horizon} — move an initiative to a
 *       Now/Next/Later bucket (write, gated, US22.3.3).</li>
 *   <li>{@code GET  .../roadmap/scale} — read the roadmap's fuzzy time scale (US22.3.2).</li>
 *   <li>{@code PUT  .../roadmap/scale} — set the roadmap's fuzzy time scale (write, gated,
 *       US22.3.2).</li>
 *   <li>{@code GET  .../roadmap/horizon-view} — read the Now/Next/Later grouped view
 *       (US22.3.3).</li>
 *   <li>{@code GET  .../roadmap/milestones} — list a project's strategic milestones, ordered by
 *       date (US22.3.4).</li>
 *   <li>{@code POST .../roadmap/milestones} — create a milestone (write, gated, US22.3.4).</li>
 *   <li>{@code PATCH .../roadmap/milestones/{milestoneId}} — move/re-lane a milestone (write,
 *       gated, US22.3.4).</li>
 * </ul>
 */
@RestController
@RequestMapping("/tenants/{tenantId}/teams/{teamId}/projects/{projectId}/roadmap")
public class RoadmapController {

    private final RoadmapService roadmapService;
    private final RoadmapEditPolicy editPolicy;

    /**
     * Constructs the controller.
     *
     * @param roadmapService the roadmap-rapide business logic
     * @param editPolicy     the role-gate extension point for writes (US22.3.1, mirrors EN18.10
     *                       écart #3)
     */
    public RoadmapController(final RoadmapService roadmapService, final RoadmapEditPolicy editPolicy) {
        this.roadmapService = roadmapService;
        this.editPolicy = editPolicy;
    }

    /**
     * Lists a project's lanes.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the ordered lanes; {@code 404} if the project is not visible
     */
    @GetMapping("/lanes")
    public ResponseEntity<List<LaneResponse>> listLanes(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(roadmapService.listLanes(tenantId, teamId, projectId));
    }

    /**
     * Creates a new lane on a project's roadmap-rapide view.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the lane creation payload
     * @return {@code 201 Created} with the created lane; {@code 403} if unauthorized;
     *         {@code 404} if the project is not visible; {@code 409} on a duplicate label
     */
    @PostMapping("/lanes")
    public ResponseEntity<LaneResponse> createLane(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @Valid @RequestBody final CreateLaneRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roadmapService.createLane(tenantId, teamId, projectId, request));
    }

    /**
     * Lists a project's roadmap-rapide initiatives.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the ordered initiatives; {@code 404} if the project is not
     *         visible
     */
    @GetMapping("/initiatives")
    public ResponseEntity<List<InitiativeResponse>> listInitiatives(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(roadmapService.listInitiatives(tenantId, teamId, projectId));
    }

    /**
     * Creates a new initiative posed on a lane.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the initiative creation payload
     * @return {@code 201 Created} with the created initiative; {@code 400} if the lane is
     *         missing/invalid or the period is inconsistent; {@code 403} if unauthorized;
     *         {@code 404} if the project is not visible
     */
    @PostMapping("/initiatives")
    public ResponseEntity<InitiativeResponse> createInitiative(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @Valid @RequestBody final CreateInitiativeRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roadmapService.createInitiative(tenantId, teamId, projectId, request));
    }

    /**
     * Moves, resizes and/or re-lanes an initiative.
     *
     * @param tenantId     the tenant's {@code public.tenants.id}
     * @param teamId       the team's {@code public.teams.id}
     * @param projectId    the project id
     * @param initiativeId the initiative (task) id
     * @param request      the placement update payload
     * @return {@code 200 OK} with the updated initiative; {@code 400} if a supplied lane is
     *         invalid or the period is inconsistent; {@code 403} if unauthorized; {@code 404} if
     *         the project or the initiative is not visible
     */
    @PatchMapping("/initiatives/{initiativeId}")
    public ResponseEntity<InitiativeResponse> updateInitiativePlacement(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long initiativeId, @RequestBody final UpdateInitiativePlacementRequest request) {
        requireEditAuthorized();
        return ResponseEntity
                .ok(roadmapService.updatePlacement(tenantId, teamId, projectId, initiativeId, request));
    }

    /**
     * Moves an initiative to a different Now/Next/Later bucket (US22.3.3).
     *
     * @param tenantId     the tenant's {@code public.tenants.id}
     * @param teamId       the team's {@code public.teams.id}
     * @param projectId    the project id
     * @param initiativeId the initiative (task) id
     * @param request      the horizon update payload
     * @return {@code 200 OK} with the updated initiative; {@code 400} if no target horizon was
     *         supplied; {@code 403} if unauthorized; {@code 404} if the project or the initiative is
     *         not visible
     */
    @PatchMapping("/initiatives/{initiativeId}/horizon")
    public ResponseEntity<InitiativeResponse> updateInitiativeHorizon(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long initiativeId, @RequestBody final UpdateInitiativeHorizonRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(roadmapService.updateHorizon(tenantId, teamId, projectId, initiativeId, request));
    }

    /**
     * Reads the roadmap's fuzzy time scale (US22.3.2) — the per-roadmap view setting, or the
     * profile-derived default (EN18.10) when unset. A read: not gated by the edit policy.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the effective scale; {@code 404} if the project is not visible
     */
    @GetMapping("/scale")
    public ResponseEntity<RoadmapScaleResponse> getScale(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(roadmapService.getScale(tenantId, teamId, projectId));
    }

    /**
     * Sets the roadmap's fuzzy time scale (US22.3.2). A view setting: it never mutates any
     * initiative's stored period. Gated as a write per the security AC (only an editor may change
     * it).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the scale update payload
     * @return {@code 200 OK} with the updated scale; {@code 400} if no scale was supplied;
     *         {@code 403} if unauthorized; {@code 404} if the project is not visible
     */
    @PutMapping("/scale")
    public ResponseEntity<RoadmapScaleResponse> updateScale(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @RequestBody final UpdateRoadmapScaleRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(roadmapService.updateScale(tenantId, teamId, projectId, request));
    }

    /**
     * Reads the Now/Next/Later grouped view of a project's initiatives (US22.3.3). A read: not
     * gated by the edit policy.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the three ordered buckets; {@code 404} if the project is not
     *         visible
     */
    @GetMapping("/horizon-view")
    public ResponseEntity<HorizonViewResponse> horizonView(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(roadmapService.listHorizonView(tenantId, teamId, projectId));
    }

    /**
     * Lists a project's strategic milestones (US22.3.4).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the ordered milestones; {@code 404} if the project is not
     *         visible
     */
    @GetMapping("/milestones")
    public ResponseEntity<List<MilestoneResponse>> listMilestones(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(roadmapService.listMilestones(tenantId, teamId, projectId));
    }

    /**
     * Creates a new strategic milestone, optionally pinned to a lane (US22.3.4).
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the milestone creation payload
     * @return {@code 201 Created} with the created milestone; {@code 400} if the date is
     *         missing/out-of-bounds or a supplied lane is invalid; {@code 403} if unauthorized;
     *         {@code 404} if the project is not visible
     */
    @PostMapping("/milestones")
    public ResponseEntity<MilestoneResponse> createMilestone(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @Valid @RequestBody final CreateMilestoneRequest request) {
        requireEditAuthorized();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roadmapService.createMilestone(tenantId, teamId, projectId, request));
    }

    /**
     * Moves a milestone to a new date and/or reassigns it to a different lane (US22.3.4).
     *
     * @param tenantId    the tenant's {@code public.tenants.id}
     * @param teamId      the team's {@code public.teams.id}
     * @param projectId   the project id
     * @param milestoneId the milestone (task) id
     * @param request     the update payload
     * @return {@code 200 OK} with the updated milestone; {@code 400} if a supplied lane is invalid
     *         or the date is out-of-bounds; {@code 403} if unauthorized; {@code 404} if the
     *         project or the milestone is not visible
     */
    @PatchMapping("/milestones/{milestoneId}")
    public ResponseEntity<MilestoneResponse> updateMilestone(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final long milestoneId, @RequestBody final UpdateMilestoneRequest request) {
        requireEditAuthorized();
        return ResponseEntity.ok(roadmapService.updateMilestone(tenantId, teamId, projectId, milestoneId, request));
    }

    /**
     * Short-circuits every write endpoint before any service call when the caller is not
     * authorized (security AC — fail-closed today, see {@link DenyAllRoadmapEditPolicy}).
     *
     * @throws RoadmapEditForbiddenException if the current caller is not authorized
     */
    private void requireEditAuthorized() {
        if (!editPolicy.isAuthorized()) {
            throw new RoadmapEditForbiddenException();
        }
    }
}

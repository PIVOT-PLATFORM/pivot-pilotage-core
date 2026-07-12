package fr.pivot.pilotage.baseline;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing baselines &amp; variance analysis of the detailed Gantt (F22.4 —
 * US22.4.9 «&nbsp;Baselines multiples &amp; analyse des écarts&nbsp;»). Thin by design (CLAUDE.md
 * §Standards): the role gate is delegated to {@link BaselineEditPolicy} and every operation to
 * {@link BaselineService}; errors are mapped by {@link BaselineExceptionHandler}.
 *
 * <p><strong>URL shape — gap-era, mirrors {@code WbsTaskController}/{@code CalendarController}.</strong>
 * {@code pivot-core-starter} (TenantContext) is not published (CLAUDE.md §gap, TODO-SETUP §5), so
 * {@code tenantId}/{@code teamId} are explicit path variables here too — never a body/query param.
 * Once the starter is consumable they move to the security context with no change to
 * {@link BaselineService}.
 *
 * <p><strong>Contract</strong>
 * <ul>
 *   <li>{@code GET    .../baselines} — list the project's baselines (read, ungated).</li>
 *   <li>{@code POST   .../baselines} — pose a baseline (write, gated). An explicit
 *       {@code baselineIndex} already in use is <em>overwritten</em> ("écraser"); an omitted index
 *       auto-assigns the lowest free slot (0..10); refused {@code 409} once all 11 are used.</li>
 *   <li>{@code DELETE .../baselines/{baselineIndex}} — delete a baseline (write, gated).</li>
 *   <li>{@code GET    .../baselines/{baselineIndex}/variance} — the baseline's per-task écarts
 *       against the current temporal graph (read, ungated — a "contributeur planning" may consult).</li>
 *   <li>{@code GET    .../baselines/{fromIndex}/compare/{toIndex}} — the evolution between two
 *       baselines, per task (read, ungated).</li>
 * </ul>
 */
@RestController
@RequestMapping("/tenants/{tenantId}/teams/{teamId}/projects/{projectId}/baselines")
public class BaselineController {

    private final BaselineService baselineService;
    private final BaselineEditPolicy editPolicy;

    /**
     * Constructs the controller.
     *
     * @param baselineService the baseline business logic
     * @param editPolicy      the role-gate extension point for writes (deny-all until the starter
     *                        publishes PMO/chef-de-projet membership, mirrors {@code WbsEditPolicy})
     */
    public BaselineController(final BaselineService baselineService, final BaselineEditPolicy editPolicy) {
        this.baselineService = baselineService;
        this.editPolicy = editPolicy;
    }

    /**
     * Lists a project's baselines.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @return {@code 200 OK} with the baselines; {@code 404} if the project is not visible
     */
    @GetMapping
    public ResponseEntity<List<BaselineResponse>> list(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId) {
        return ResponseEntity.ok(baselineService.listBaselines(tenantId, teamId, projectId));
    }

    /**
     * Poses (or, when the slot is already used, overwrites) a baseline.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param request   the creation payload ({@code baselineIndex}, or an absent/empty body to
     *                  auto-assign the lowest free slot)
     * @return {@code 201 Created} with the posed baseline; {@code 403} if unauthorized; {@code 404}
     *         if the project is not visible; {@code 422} if the supplied index is outside
     *         {@code 0..10}; {@code 409} if the index is omitted and all 11 slots are already used
     */
    @PostMapping
    public ResponseEntity<BaselineResponse> setBaseline(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @RequestBody(required = false) final SetBaselineRequest request) {
        requireEditAuthorized();
        final Short requestedIndex = request != null ? request.baselineIndex() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(baselineService.setBaseline(tenantId, teamId, projectId, requestedIndex));
    }

    /**
     * Deletes a baseline.
     *
     * @param tenantId      the tenant's {@code public.tenants.id}
     * @param teamId        the team's {@code public.teams.id}
     * @param projectId     the project id
     * @param baselineIndex the baseline slot to delete
     * @return {@code 204 No Content}; {@code 403} if unauthorized; {@code 404} if not visible
     */
    @DeleteMapping("/{baselineIndex}")
    public ResponseEntity<Void> deleteBaseline(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final short baselineIndex) {
        requireEditAuthorized();
        baselineService.deleteBaseline(tenantId, teamId, projectId, baselineIndex);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reads a baseline's per-task écarts against the current temporal graph. A read — not gated by
     * the edit policy, only by tenant/team/project isolation (security AC: a read-only contributeur
     * planning may consult écarts).
     *
     * @param tenantId      the tenant's {@code public.tenants.id}
     * @param teamId        the team's {@code public.teams.id}
     * @param projectId     the project id
     * @param baselineIndex the baseline slot to compare against
     * @return {@code 200 OK} with the variance report; {@code 404} if not visible
     */
    @GetMapping("/{baselineIndex}/variance")
    public ResponseEntity<BaselineVarianceResponse> variance(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final short baselineIndex) {
        return ResponseEntity.ok(baselineService.variance(tenantId, teamId, projectId, baselineIndex));
    }

    /**
     * Compares two baselines directly. A read — not gated by the edit policy, only by tenant/team/
     * project isolation.
     *
     * @param tenantId  the tenant's {@code public.tenants.id}
     * @param teamId    the team's {@code public.teams.id}
     * @param projectId the project id
     * @param fromIndex the earlier/reference baseline slot
     * @param toIndex   the later/compared-to baseline slot
     * @return {@code 200 OK} with the comparison report; {@code 404} if either index is not visible
     */
    @GetMapping("/{fromIndex}/compare/{toIndex}")
    public ResponseEntity<BaselineComparisonResponse> compare(@PathVariable final long tenantId,
            @PathVariable final long teamId, @PathVariable final long projectId,
            @PathVariable final short fromIndex, @PathVariable final short toIndex) {
        return ResponseEntity.ok(baselineService.compare(tenantId, teamId, projectId, fromIndex, toIndex));
    }

    /**
     * Short-circuits every write endpoint before any service call when the caller is not authorized
     * (security AC — fail-closed today, see {@link DenyAllBaselineEditPolicy}).
     *
     * @throws BaselineEditForbiddenException if the current caller is not authorized
     */
    private void requireEditAuthorized() {
        if (!editPolicy.isAuthorized()) {
            throw new BaselineEditForbiddenException();
        }
    }
}

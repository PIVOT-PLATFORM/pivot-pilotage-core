/**
 * Baselines &amp; variance analysis of the detailed Gantt (F22.4 — US22.4.9 «&nbsp;Baselines
 * multiples &amp; analyse des écarts&nbsp;»).
 *
 * <p>Exposes the MS-Project-parity surface on top of the EN22.1a frozen contract §a
 * ({@code pilotage.baseline} / {@code pilotage.baseline_snapshot}, already schema-owned by
 * {@link fr.pivot.pilotage.baseline.Baseline}/{@link fr.pivot.pilotage.baseline.BaselineSnapshot}):
 * <ul>
 *   <li><strong>pose / écrase</strong> — {@code POST .../baselines} freezes every task's
 *       start/finish/duration/work/cost (and altitude, {@code bl_temporal_precision}) into a new
 *       slot (0..10, up to 11 baselines as in MS Project — {@code Baseline} + {@code Baseline 1..10});
 *       an explicit {@code baselineIndex} already in use is <em>overwritten</em> (the prior baseline
 *       row and its snapshots are deleted, a fresh one is captured) — this is the "écraser" action of
 *       the security AC, not a separate endpoint. Omitting the index auto-assigns the lowest free
 *       slot; once all 11 are used, the caller is refused (409) and invited to overwrite or delete
 *       one first;</li>
 *   <li><strong>supprime</strong> — {@code DELETE .../baselines/{baselineIndex}} removes a baseline;
 *       its snapshots cascade via the DB FK ({@code ON DELETE CASCADE});</li>
 *   <li><strong>écarts (variance)</strong> — {@code GET .../baselines/{baselineIndex}/variance}
 *       compares one baseline's frozen values to the <em>current</em> values of the same temporal
 *       graph (EN22.1) — never recomputing the baseline itself, per the US notes;</li>
 *   <li><strong>évolution entre baselines (compare)</strong> —
 *       {@code GET .../baselines/{fromIndex}/compare/{toIndex}} diffs two frozen snapshots directly,
 *       no "current" values involved.</li>
 * </ul>
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> This package owns no scheduling maths: the
 * "current" values it compares a baseline against are simply the persisted
 * {@code fr.pivot.pilotage.schedule.Task}/{@code fr.pivot.pilotage.schedule.Assignment} rows, already
 * kept accurate by the EN22.1b engine on every write elsewhere in the Gantt. Snapshot capture is a
 * lightweight, batched field copy (two queries — all tasks, then all their assignments in one
 * {@code IN (...)} call — never one query per task) so posing a baseline on a 10 000+ task plan stays
 * a bounded, synchronous operation (EN22.2 perf note), not a blocking full duplication.
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and {@code TODO-SETUP.md} §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId} are
 * explicit path variables, never taken from a request body. Every project/baseline is resolved
 * through a tenant+team-scoped lookup collapsing every isolation failure into one non-disclosing 404
 * ({@link fr.pivot.pilotage.baseline.BaselineProjectNotFoundException} /
 * {@link fr.pivot.pilotage.baseline.BaselineNotFoundException}).
 *
 * <p><strong>Security AC.</strong> Posing/overwriting/deleting a baseline is a write, gated by
 * {@link fr.pivot.pilotage.baseline.BaselineEditPolicy} (fail-closed today via
 * {@link fr.pivot.pilotage.baseline.DenyAllBaselineEditPolicy}, mirrors
 * {@code fr.pivot.pilotage.gantt.WbsEditPolicy} exactly, pending real PMO/chef-de-projet role
 * resolution from {@code pivot-core-starter}). Reading baselines/variance/comparison is
 * <strong>not</strong> gated by this policy — only by tenant/team/project isolation — so a
 * read-only "contributeur planning" can consult écarts without an edit role, per the US security AC.
 *
 * <p><strong>A11y AC.</strong> Every numeric variance ({@code TaskVarianceResponse},
 * {@code BaselineComparisonRowResponse}) is paired with a French textual label (e.g. {@code "Début en
 * retard de 3 j"}, {@code "Coût en économie de 120,00 (-4 %)"}) so a positive/negative variance is
 * never conveyed by sign or colour alone — mirrors the {@code progressLabel} convention already
 * established by {@code fr.pivot.pilotage.gantt.WbsTaskResponse}. Keyboard navigation of the rendered
 * comparison table is a {@code pivot-pilotage-ui} concern (not yet created) — this backend's
 * contribution is limited to the colour-independent data it returns.
 *
 * <p>Follows the {@code fr.pivot.pilotage.roadmap}/{@code fr.pivot.pilotage.gantt} REST pattern: a
 * thin controller, a service holding the logic, DTOs (never entities), tenant/team explicit
 * arguments (pre-starter gap), a fail-closed edit policy (403) on writes only, non-disclosing 404 on
 * cross-tenant/team access, and a package-scoped exception handler.
 */
package fr.pivot.pilotage.baseline;

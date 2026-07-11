package fr.pivot.pilotage.roadmap;

import java.util.List;

/**
 * The Now/Next/Later grouped view of a project's roadmap-rapide initiatives (US22.3.3 — {@code GET
 * .../roadmap/horizon-view}). An alternative projection over the same initiatives as the temporal
 * roadmap (US22.3.1) — no separate data structure, only a different rendering ("bascule roadmap
 * temporelle ↔ Now/Next/Later : même jeu d'initiatives, changement de rendu uniquement").
 *
 * <p>The three concrete buckets are always present and always in {@code NOW}, {@code NEXT},
 * {@code LATER} order, each possibly empty — the frontend renders three fixed columns. Initiatives
 * that carry no horizon yet ({@code horizon} column {@code null} — e.g. created before US22.3.3
 * assigned a default) are surfaced in {@link #unbucketed} rather than silently dropped, so no
 * initiative is lost when switching to this view.
 *
 * @param buckets    the {@code NOW}/{@code NEXT}/{@code LATER} columns, in that fixed order (each
 *                   possibly empty; defensively copied)
 * @param unbucketed initiatives with no horizon assigned yet, in the same intra-group order
 *                   (defensively copied)
 */
public record HorizonViewResponse(List<HorizonBucketResponse> buckets, List<InitiativeResponse> unbucketed) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies (SpotBugs {@code EI_EXPOSE_REP} —
     * applied preventively; the record accessor pattern only surfaces under {@code --release 24}).
     *
     * @param buckets    the ordered horizon columns
     * @param unbucketed the initiatives with no horizon assigned yet
     */
    public HorizonViewResponse {
        buckets = List.copyOf(buckets);
        unbucketed = List.copyOf(unbucketed);
    }
}

package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.Horizon;

import java.util.List;

/**
 * One Now/Next/Later column of the roadmap horizon view (US22.3.3) — a horizon bucket and the
 * initiatives it holds, in intra-bucket order.
 *
 * <p><strong>Intra-bucket order</strong> (per the AC "les initiatives se rangent en colonnes par
 * horizon"): initiatives are ordered by their lane's display position, then by their own position
 * within the lane, then by id — the same stable order {@link RoadmapService#listInitiatives} uses
 * for the temporal view, so switching from the temporal roadmap to Now/Next/Later never reshuffles
 * an initiative relative to its peers ("même jeu d'initiatives, changement de rendu uniquement").
 *
 * @param horizon     the bucket key ({@code NOW}/{@code NEXT}/{@code LATER})
 * @param initiatives the initiatives in this bucket, in intra-bucket order (defensively copied)
 */
public record HorizonBucketResponse(Horizon horizon, List<InitiativeResponse> initiatives) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of {@code initiatives} (SpotBugs
     * {@code EI_EXPOSE_REP} — applied preventively; the record accessor pattern only surfaces under
     * {@code --release 24}).
     *
     * @param horizon     the bucket key
     * @param initiatives the initiatives in this bucket
     */
    public HorizonBucketResponse {
        initiatives = List.copyOf(initiatives);
    }
}

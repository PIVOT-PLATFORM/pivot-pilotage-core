package fr.pivot.pilotage.roadmap;

import java.util.List;
import java.util.Objects;

/**
 * Response DTO for the public, unauthenticated read-only roadmap view (US22.3.5,
 * {@code GET /public/roadmap-shares/{token}}).
 *
 * <p>Deliberately reuses {@link LaneResponse}/{@link InitiativeResponse} — the exact same shape
 * an authenticated caller sees via {@code RoadmapController} — so the recipient's read-only view
 * is a strict subset of the live roadmap (never a divergent projection), and no
 * lane/initiative-mapping logic is duplicated. Scoped to the export contract as it exists today
 * (lanes + initiatives); intentionally extensible with an additive field (e.g. milestones, once
 * US22.3.4 lands) without breaking this contract.
 *
 * @param projectName the name of the project this share link points to — gives the recipient
 *                     context without granting access to any other project/portfolio data
 * @param lanes        the project's lanes, ordered by display position
 * @param initiatives  the project's roadmap-rapide initiatives, ordered by lane then position
 */
public record RoadmapShareViewResponse(String projectName, List<LaneResponse> lanes,
        List<InitiativeResponse> initiatives) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies of {@code lanes}/
     * {@code initiatives} — mirrors {@code fr.pivot.pilotage.schedule.engine.ChangeSet}'s
     * established convention for a record carrying a mutable {@link List} component.
     *
     * @throws NullPointerException if {@code lanes} or {@code initiatives} is null
     */
    public RoadmapShareViewResponse {
        lanes = List.copyOf(Objects.requireNonNull(lanes, "lanes"));
        initiatives = List.copyOf(Objects.requireNonNull(initiatives, "initiatives"));
    }
}

package fr.pivot.pilotage.roadmap;

/**
 * Response DTO for a {@link Lane} (US22.3.1) — never the JPA entity directly (CLAUDE.md
 * §Standards).
 *
 * @param id       the lane id
 * @param name     the lane label (theme / team / objective)
 * @param position the display order within its project
 */
public record LaneResponse(long id, String name, int position) {

    /**
     * Maps a {@link Lane} entity to its response DTO.
     *
     * @param lane the persisted lane
     * @return the mapped response
     */
    static LaneResponse from(final Lane lane) {
        return new LaneResponse(lane.getId(), lane.getName(), lane.getPosition());
    }
}

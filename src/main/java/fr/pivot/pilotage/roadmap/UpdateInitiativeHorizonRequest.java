package fr.pivot.pilotage.roadmap;

import fr.pivot.pilotage.schedule.Horizon;

/**
 * Request body of {@code PATCH .../roadmap/initiatives/{initiativeId}/horizon} (US22.3.3) — moves
 * an initiative from one Now/Next/Later bucket to another (the keyboard/drag-and-drop "changement de
 * bucket" gesture; the drag itself is pivot-pilotage-ui's concern, this is its single write path).
 *
 * <p>{@code horizon} is required but deliberately not {@code @NotNull}: a {@code null} token is
 * rejected explicitly by {@code RoadmapService} ({@link InvalidHorizonException}) so the 400 carries
 * a caller-facing {@link ApiError} body — same rationale as {@link CreateInitiativeRequest#laneId()}.
 * A move can only target a concrete bucket (NOW/NEXT/LATER); un-bucketising an initiative back to
 * {@code null} is out of scope for this US.
 *
 * @param horizon the target Now/Next/Later bucket; required
 */
public record UpdateInitiativeHorizonRequest(Horizon horizon) {
}

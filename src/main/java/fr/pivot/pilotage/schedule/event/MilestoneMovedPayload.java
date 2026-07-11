package fr.pivot.pilotage.schedule.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Payload of {@link PlanEventType#MILESTONE_MOVED} (EN22.1c, frozen contract §d) — emitted when a
 * milestone's date changes. A shared milestone carries {@code altitude=["macro","detail"]}: one
 * event reflects the move in both views (no parallel write). Consumers: US22.3.4, E23, E24.
 *
 * @param milestoneId the milestone node id (stable across both views)
 * @param oldDate     the previous date, or {@code null} if newly dated
 * @param newDate     the new date
 * @param altitudes   the altitudes the milestone is projected into ({@code macro} and/or {@code
 *                    detail}) — {@code ["macro","detail"]} for a shared milestone
 */
public record MilestoneMovedPayload(
        long milestoneId,
        Instant oldDate,
        Instant newDate,
        List<String> altitudes) implements PlanEventPayload {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the altitude list.
     *
     * @throws NullPointerException if {@code altitudes} is {@code null}
     */
    public MilestoneMovedPayload {
        altitudes = List.copyOf(Objects.requireNonNull(altitudes, "altitudes"));
    }
}

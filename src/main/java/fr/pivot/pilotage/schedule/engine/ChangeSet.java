package fr.pivot.pilotage.schedule.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An atomic, ordered, inversible list of {@link ChangeOp}s applied against a base version (EN22.1b).
 *
 * <p>Atomicity: {@code reSchedule} applies all ops or none — a cycle-introducing set is rejected
 * whole (patch empty). Optimistic co-editing: {@code baseVersion} must equal the previous state's
 * version or {@link ScheduleErrorCode#STALE_BASE_VERSION} is raised. An empty change set yields an
 * empty patch (idempotence D3).
 *
 * @param baseVersion the schedule version this delta is built against
 * @param ops         the atomic operations, in application order
 */
public record ChangeSet(long baseVersion, List<ChangeOp> ops) {

    /**
     * Canonical constructor taking a defensive, unmodifiable copy of the ops.
     *
     * @throws NullPointerException if {@code ops} is null
     */
    public ChangeSet {
        ops = List.copyOf(Objects.requireNonNull(ops, "ops"));
    }

    /**
     * Returns whether this change set carries no operations (⇒ empty patch, D3).
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return ops.isEmpty();
    }

    /**
     * Returns the inverse change set (undo), stamped for the version this delta produces.
     *
     * @param before         the state before this delta
     * @param producedVersion the version this delta produces (its inverse's base)
     * @return the inverse change set
     */
    public ChangeSet inverse(final ScheduleState before, final long producedVersion) {
        final List<ChangeOp> inv = new ArrayList<>();
        for (int i = ops.size() - 1; i >= 0; i--) {
            inv.add(ops.get(i).inverse(before));
        }
        return new ChangeSet(producedVersion, inv);
    }
}

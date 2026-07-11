package fr.pivot.pilotage.schedule.engine;

/**
 * Stable, deterministic content hash of a {@link ScheduleInput} (EN22.1b) used for optimistic
 * co-editing: {@code reSchedule} verifies the caller's base version against the hash of the state it
 * was computed from. Pure — no {@code now()}, no identity hash, no insertion-order dependence
 * (tasks and dependencies are hashed in canonical tie-break order, supporting determinism D1).
 */
final class InputHash {

    private InputHash() {
    }

    /**
     * Computes the content hash of an input.
     *
     * @param input the input
     * @return a stable 64-bit hash
     */
    static long of(final ScheduleInput input) {
        long h = 1125899906842597L; // FNV-ish seed
        h = mix(h, input.projectId());
        h = mix(h, input.tenantId());
        h = mix(h, input.projectStart().toEpochMilli());
        h = mix(h, input.defaultCalendarId());
        for (final TaskNode t : input.tasksInCanonicalOrder()) {
            h = mix(h, t.id());
            h = mix(h, t.durationMinutes());
            h = mix(h, t.mode().ordinal());
            h = mix(h, t.nodeType().ordinal());
            h = mix(h, t.calendarId());
            h = mix(h, t.parentId() == null ? -1L : t.parentId());
            h = mix(h, t.constraintKind() == null ? -1L : t.constraintKind().ordinal());
            h = mix(h, t.constraintDate() == null ? -1L : t.constraintDate().toEpochMilli());
            h = mix(h, t.manualStart() == null ? -1L : t.manualStart().toEpochMilli());
            h = mix(h, t.manualFinish() == null ? -1L : t.manualFinish().toEpochMilli());
        }
        for (final DependencyEdge e : input.dependenciesInCanonicalOrder()) {
            h = mix(h, e.predecessorId());
            h = mix(h, e.successorId());
            h = mix(h, e.linkType().ordinal());
            h = mix(h, e.lagMinutes());
        }
        return h;
    }

    private static long mix(final long acc, final long value) {
        long h = acc ^ value;
        h *= 1099511628211L; // FNV prime
        h ^= (h >>> 29);
        return h;
    }
}

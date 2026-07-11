package fr.pivot.pilotage.schedule.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, mono-tenant snapshot of one project's temporal graph — the sole argument of
 * {@link ScheduleEngine#schedule(ScheduleInput)} (EN22.1b, frozen contract §b).
 *
 * <p>The input is <strong>self-contained</strong>: it carries no FK, performs no inter-module read,
 * and mixes no tenant. Any external datum (resource availability, third-party calendars) must be
 * folded in by the caller before construction (ADR-006). The engine never consults {@code now()};
 * the {@code dataDate} (status date) fixes the "actual before" boundary and is supplied here.
 *
 * @param projectId     the project this snapshot describes
 * @param tenantId      the single owning tenant (isolation is verified by the engine)
 * @param dataDate      the status/data date; realised work before it is pinned
 * @param projectStart  the project scheduling anchor (earliest possible start) — required
 * @param defaultCalendarId the project default calendar id
 * @param tasks         the tasks (order-insensitive; the engine sorts by {@link TaskNode#tieBreakKey()})
 * @param dependencies  the dependency edges
 * @param calendars     calendars by id (must include every referenced calendar)
 */
public record ScheduleInput(
        long projectId,
        long tenantId,
        Instant dataDate,
        Instant projectStart,
        long defaultCalendarId,
        List<TaskNode> tasks,
        List<DependencyEdge> dependencies,
        Map<Long, WorkingCalendar> calendars) {

    /**
     * Canonical constructor taking defensive, unmodifiable copies.
     *
     * @throws NullPointerException if any collection or the project start is null
     */
    public ScheduleInput {
        Objects.requireNonNull(projectStart, "projectStart");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        calendars = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(calendars, "calendars")));
    }

    /**
     * Resolves the working calendar for a task, falling back to the project default when the task
     * references an unknown or absent calendar.
     *
     * @param task the task
     * @return the resolved calendar
     * @throws ScheduleException {@code UNKNOWN_CALENDAR} if no calendar can be resolved at all
     */
    public WorkingCalendar calendarFor(final TaskNode task) {
        WorkingCalendar cal = calendars.get(task.calendarId());
        if (cal == null) {
            cal = calendars.get(defaultCalendarId);
        }
        if (cal == null) {
            throw new ScheduleException(ScheduleErrorCode.UNKNOWN_CALENDAR,
                    "no calendar for task " + task.id() + " (calendarId=" + task.calendarId()
                            + ", default=" + defaultCalendarId + ")");
        }
        return cal;
    }

    /**
     * Returns the tasks sorted by the deterministic tie-break key (D2) — a stable, total order
     * independent of insertion order.
     *
     * @return a new list of tasks in canonical order
     */
    public List<TaskNode> tasksInCanonicalOrder() {
        final List<TaskNode> sorted = new ArrayList<>(tasks);
        sorted.sort((a, b) -> a.tieBreakKey().compareTo(b.tieBreakKey()));
        return sorted;
    }

    /**
     * Returns the dependencies sorted by a stable key (predecessor, successor, link type) for
     * deterministic iteration.
     *
     * @return a new list of dependencies in canonical order
     */
    public List<DependencyEdge> dependenciesInCanonicalOrder() {
        final List<DependencyEdge> sorted = new ArrayList<>(dependencies);
        sorted.sort((a, b) -> {
            final int p = Long.compare(a.predecessorId(), b.predecessorId());
            if (p != 0) {
                return p;
            }
            final int s = Long.compare(a.successorId(), b.successorId());
            if (s != 0) {
                return s;
            }
            return a.linkType().compareTo(b.linkType());
        });
        return sorted;
    }
}

package fr.pivot.pilotage.schedule;

/**
 * Scope a working-time calendar applies to (EN22.1a, frozen contract §a).
 */
public enum CalendarScope {

    /** Project-level default calendar. */
    PROJECT,

    /** Task-specific calendar override. */
    TASK,

    /** Resource-specific calendar. */
    RESOURCE
}

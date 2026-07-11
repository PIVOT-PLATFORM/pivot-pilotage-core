package fr.pivot.pilotage.schedule;

/**
 * Dependency link type between two tasks (EN22.1a, frozen contract §a).
 */
public enum DependencyLinkType {

    /** Finish-to-start: the successor starts after the predecessor finishes. */
    FS,

    /** Start-to-start: the successor starts after the predecessor starts. */
    SS,

    /** Finish-to-finish: the successor finishes after the predecessor finishes. */
    FF,

    /** Start-to-finish: the successor finishes after the predecessor starts. */
    SF
}

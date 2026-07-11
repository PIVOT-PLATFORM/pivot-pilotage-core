package fr.pivot.pilotage.schedule.engine;

/**
 * Dependency link type as consumed by the engine (EN22.1b) — a pure-core mirror of
 * {@code fr.pivot.pilotage.schedule.DependencyLinkType} so the engine core stays independent of the
 * persistence layer.
 */
public enum LinkType {

    /** Finish-to-start: the successor starts after the predecessor finishes. */
    FS,

    /** Start-to-start: the successor starts after the predecessor starts. */
    SS,

    /** Finish-to-finish: the successor finishes after the predecessor finishes. */
    FF,

    /** Start-to-finish: the successor finishes after the predecessor starts. */
    SF
}

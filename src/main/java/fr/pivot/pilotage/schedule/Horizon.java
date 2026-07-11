package fr.pivot.pilotage.schedule;

/**
 * Now/Next/Later planning horizon of a high-level (initiative) node (EN22.1c, frozen contract
 * §c/§e — column {@code pilotage.task.horizon}, nullable).
 *
 * <p>This is the bucket key of the macro "Now/Next/Later" view (US22.3.3): the node carries the
 * attribute directly, with no temporal axis and no dedicated table. {@code null} means the node is
 * not bucketised. A change of bucket emits a {@code HorizonChanged} event (§d).
 */
public enum Horizon {

    /** Current focus — the work happening now. */
    NOW,

    /** The next commitment — planned but not yet started. */
    NEXT,

    /** Later — on the radar, not yet committed. */
    LATER
}

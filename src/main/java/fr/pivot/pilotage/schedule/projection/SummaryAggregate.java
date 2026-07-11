package fr.pivot.pilotage.schedule.projection;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable rollup of a {@code SUMMARY} node's children (EN22.1c, frozen contract §c/§e —
 * US22.4.1c). <strong>Derived in projection, never persisted twice</strong> on the summary row:
 * {@code start = min(children)}, {@code finish = max(children)}, {@code work/cost = Σ},
 * {@code percentComplete = }charge-weighted mean, {@code critical = }true if ≥1 leaf is critical.
 *
 * @param summaryId       the summary node id
 * @param rollupStart     earliest child start, or {@code null} if no dated child
 * @param rollupFinish    latest child finish, or {@code null} if no dated child
 * @param totalWorkMinutes summed planned work of the descendants (worked minutes)
 * @param totalCostAmount summed planned cost of the descendants, or {@code null} if none
 * @param percentComplete charge-weighted percent complete in {@code [0, 100]}
 * @param critical        whether at least one descendant leaf is on the critical path
 * @param leafCount       number of leaf descendants folded into this rollup
 */
public record SummaryAggregate(
        long summaryId,
        Instant rollupStart,
        Instant rollupFinish,
        long totalWorkMinutes,
        BigDecimal totalCostAmount,
        BigDecimal percentComplete,
        boolean critical,
        int leafCount) {
}

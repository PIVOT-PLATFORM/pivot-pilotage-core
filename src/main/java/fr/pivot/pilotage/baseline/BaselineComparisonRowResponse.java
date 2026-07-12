package fr.pivot.pilotage.baseline;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@code GET .../baselines/{fromIndex}/compare/{toIndex}} (US22.4.9) — a single task's
 * frozen values in the {@code from} baseline against the same task's frozen values in the
 * {@code to} baseline (no "current" value involved, unlike {@link TaskVarianceResponse}).
 *
 * <p>A task present in only one of the two baselines (created or removed between the two captures)
 * carries {@code null} on the absent side; every delta is then itself {@code null} ("non
 * comparable"). Every numeric delta is paired with a French, colour-independent textual label (A11y
 * AC, same convention as {@link TaskVarianceResponse}).
 *
 * @param taskId                the task id
 * @param taskName               the task's current name, or {@code null} if it no longer exists
 * @param fromStart              {@code from} baseline's frozen start, or {@code null}
 * @param toStart                {@code to} baseline's frozen start, or {@code null}
 * @param startDeltaMinutes      {@code toStart - fromStart} in minutes, or {@code null}
 * @param startDeltaLabel        colour-independent rendering of the start delta
 * @param fromFinish             {@code from} baseline's frozen finish, or {@code null}
 * @param toFinish               {@code to} baseline's frozen finish, or {@code null}
 * @param finishDeltaMinutes     {@code toFinish - fromFinish} in minutes, or {@code null}
 * @param finishDeltaLabel       colour-independent rendering of the finish delta
 * @param fromDurationMinutes    {@code from} baseline's frozen duration, or {@code null}
 * @param toDurationMinutes      {@code to} baseline's frozen duration, or {@code null}
 * @param durationDeltaMinutes   {@code toDuration - fromDuration}, or {@code null}
 * @param durationDeltaPercent   the duration delta as a percentage of {@code fromDuration}, or
 *                               {@code null} if not computable
 * @param durationDeltaLabel     colour-independent rendering of the duration delta
 * @param fromWorkMinutes        {@code from} baseline's frozen total work, or {@code null}
 * @param toWorkMinutes          {@code to} baseline's frozen total work, or {@code null}
 * @param workDeltaMinutes       {@code toWork - fromWork}, or {@code null}
 * @param workDeltaPercent       the work delta as a percentage of {@code fromWork}, or {@code null}
 *                               if not computable
 * @param workDeltaLabel         colour-independent rendering of the work delta
 * @param fromCostAmount         {@code from} baseline's frozen cost, or {@code null}
 * @param toCostAmount           {@code to} baseline's frozen cost, or {@code null}
 * @param costDeltaAmount        {@code toCost - fromCost}, or {@code null}
 * @param costDeltaPercent       the cost delta as a percentage of {@code fromCost}, or {@code null}
 *                               if not computable
 * @param costDeltaLabel         colour-independent rendering of the cost delta
 */
public record BaselineComparisonRowResponse(
        long taskId,
        String taskName,
        Instant fromStart,
        Instant toStart,
        Long startDeltaMinutes,
        String startDeltaLabel,
        Instant fromFinish,
        Instant toFinish,
        Long finishDeltaMinutes,
        String finishDeltaLabel,
        Integer fromDurationMinutes,
        Integer toDurationMinutes,
        Integer durationDeltaMinutes,
        BigDecimal durationDeltaPercent,
        String durationDeltaLabel,
        Integer fromWorkMinutes,
        Integer toWorkMinutes,
        Integer workDeltaMinutes,
        BigDecimal workDeltaPercent,
        String workDeltaLabel,
        BigDecimal fromCostAmount,
        BigDecimal toCostAmount,
        BigDecimal costDeltaAmount,
        BigDecimal costDeltaPercent,
        String costDeltaLabel) {
}

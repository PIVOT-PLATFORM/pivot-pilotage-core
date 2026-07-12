package fr.pivot.pilotage.baseline;

import fr.pivot.pilotage.schedule.TemporalPrecision;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@code GET .../baselines/{baselineIndex}/variance} (US22.4.9) — a single task's frozen
 * baseline values against its <em>current</em> values on the live temporal graph (EN22.1), never a
 * recomputed baseline.
 *
 * <p>Every numeric variance is paired with a French, colour-independent textual label (A11y AC —
 * mirrors the {@code progressLabel} convention of {@code fr.pivot.pilotage.gantt.WbsTaskResponse}) so
 * a positive/negative écart is never conveyed by sign or colour alone. Date variances
 * ({@link #startVarianceMinutes}/{@link #finishVarianceMinutes}) are carried in minutes for
 * consistency with the rest of this API's variance fields (e.g. {@code ManualVariance.deltaMinutes});
 * their labels render the day-level figure the AC asks for ("écarts en jours"). Duration/work
 * variances are additionally expressed as a percentage, and cost as both an amount and a percentage —
 * matching the AC's "écarts (jours, %, coût)".
 *
 * <p>A {@code null} current* field means the task's current value is itself {@code null} (e.g. no
 * date scheduled yet) — the corresponding variance and label report "non comparable" rather than
 * guessing.
 *
 * @param taskId                      the task id
 * @param taskName                    the task's current name
 * @param baselineStart               the frozen start ({@code bl_start}), or {@code null}
 * @param currentStart                the task's current start date, or {@code null}
 * @param startVarianceMinutes        {@code currentStart - baselineStart} in minutes, or
 *                                    {@code null} if either side is absent
 * @param startVarianceLabel          colour-independent rendering of the start variance
 * @param baselineFinish              the frozen finish ({@code bl_finish}), or {@code null}
 * @param currentFinish               the task's current finish date, or {@code null}
 * @param finishVarianceMinutes       {@code currentFinish - baselineFinish} in minutes, or
 *                                    {@code null} if either side is absent
 * @param finishVarianceLabel         colour-independent rendering of the finish variance
 * @param baselineDurationMinutes     the frozen duration ({@code bl_duration_minutes}), or
 *                                    {@code null}
 * @param currentDurationMinutes      the task's current duration, or {@code null}
 * @param durationVarianceMinutes     {@code currentDuration - baselineDuration}, or {@code null}
 * @param durationVariancePercent     the duration variance as a percentage of the baseline duration,
 *                                    or {@code null} if not computable (baseline absent or zero)
 * @param durationVarianceLabel       colour-independent rendering of the duration variance
 * @param baselineWorkMinutes         the frozen total work ({@code bl_work_minutes}), or {@code null}
 * @param currentWorkMinutes          the task's current total work (Σ assignments), or {@code null}
 * @param workVarianceMinutes         {@code currentWork - baselineWork}, or {@code null}
 * @param workVariancePercent         the work variance as a percentage of the baseline work, or
 *                                    {@code null} if not computable
 * @param workVarianceLabel           colour-independent rendering of the work variance
 * @param baselineCostAmount          the frozen cost ({@code bl_cost_amount}), or {@code null}
 * @param currentCostAmount           the task's current total cost (Σ assignments), or {@code null}
 * @param costVarianceAmount          {@code currentCost - baselineCost}, or {@code null}
 * @param costVariancePercent         the cost variance as a percentage of the baseline cost, or
 *                                    {@code null} if not computable
 * @param costVarianceLabel           colour-independent rendering of the cost variance
 * @param baselineTemporalPrecision   the frozen altitude ({@code bl_temporal_precision}), or
 *                                    {@code null}
 * @param currentTemporalPrecision    the task's current altitude, or {@code null}
 * @param temporalPrecisionChanged    whether the altitude changed since the baseline was captured —
 *                                    when {@code true}, date/duration variances above should be read
 *                                    with that context (comparing a fuzzy baseline to a precise
 *                                    current value, or vice versa)
 */
public record TaskVarianceResponse(
        long taskId,
        String taskName,
        Instant baselineStart,
        Instant currentStart,
        Long startVarianceMinutes,
        String startVarianceLabel,
        Instant baselineFinish,
        Instant currentFinish,
        Long finishVarianceMinutes,
        String finishVarianceLabel,
        Integer baselineDurationMinutes,
        Integer currentDurationMinutes,
        Integer durationVarianceMinutes,
        BigDecimal durationVariancePercent,
        String durationVarianceLabel,
        Integer baselineWorkMinutes,
        Integer currentWorkMinutes,
        Integer workVarianceMinutes,
        BigDecimal workVariancePercent,
        String workVarianceLabel,
        BigDecimal baselineCostAmount,
        BigDecimal currentCostAmount,
        BigDecimal costVarianceAmount,
        BigDecimal costVariancePercent,
        String costVarianceLabel,
        TemporalPrecision baselineTemporalPrecision,
        TemporalPrecision currentTemporalPrecision,
        boolean temporalPrecisionChanged) {
}

package fr.pivot.pilotage.baseline;

import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.Assignment;
import fr.pivot.pilotage.schedule.AssignmentRepository;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic backing {@link BaselineController} — pose/overwrite/delete of baselines and their
 * écarts analysis against the current temporal graph (US22.4.9).
 *
 * <p><strong>Reuse, not reinvention (Étape 0).</strong> This service never recomputes a schedule: it
 * reads the already-accurate {@link Task}/{@link Assignment} rows maintained elsewhere by the
 * EN22.1b engine. Snapshot capture batches two queries (all of a project's tasks, then all of their
 * assignments in a single {@code IN (...)} call) rather than one query per task, so posing a baseline
 * on a 10 000+ task plan stays bounded (EN22.2 perf note, US notes).
 *
 * <p><strong>Tenant/team isolation.</strong> Per CLAUDE.md §gap and {@code TODO-SETUP.md} §5,
 * {@code pivot-core-starter} (TenantContext) is not published, so {@code tenantId}/{@code teamId} are
 * explicit arguments, never taken from a request body. Every project/baseline is resolved through a
 * tenant+team-scoped lookup collapsing every isolation failure into one non-disclosing
 * {@link BaselineProjectNotFoundException}/{@link BaselineNotFoundException} (404).
 */
@Service
public class BaselineService {

    private static final Logger LOG = LoggerFactory.getLogger(BaselineService.class);

    /** Lowest valid baseline slot (MS Project's plain {@code Baseline}). */
    private static final short MIN_INDEX = 0;

    /** Highest valid baseline slot (MS Project's {@code Baseline 10}) — 11 slots total. */
    private static final short MAX_INDEX = 10;

    /** Minutes in a calendar day, used to render a minute-granularity variance as a day count. */
    private static final long MINUTES_PER_DAY = 24 * 60L;

    /** Percent basis for variance-percentage computations. */
    private static final BigDecimal PERCENT = new BigDecimal("100");

    /** Decimal scale kept on rendered percentages. */
    private static final int PERCENT_SCALE = 2;

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final BaselineRepository baselineRepository;
    private final BaselineSnapshotRepository snapshotRepository;

    /**
     * Constructs the service.
     *
     * @param projectRepository  tenant/team-scoped project repository (isolation boundary)
     * @param taskRepository     tenant/team-scoped task repository (current values, snapshot source)
     * @param assignmentRepository tenant/team-scoped assignment repository (work/cost aggregation)
     * @param baselineRepository tenant/team-scoped baseline repository (EN22.1a)
     * @param snapshotRepository tenant/team-scoped baseline-snapshot repository (EN22.1a)
     */
    public BaselineService(final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final AssignmentRepository assignmentRepository, final BaselineRepository baselineRepository,
            final BaselineSnapshotRepository snapshotRepository) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.baselineRepository = baselineRepository;
        this.snapshotRepository = snapshotRepository;
    }

    // ---- pose / overwrite / delete / list ---------------------------------------------------------

    /**
     * Poses a baseline: freezes every task's start/finish/duration/work/cost/altitude into a new
     * slot. When {@code requestedIndex} already carries a baseline it is <em>overwritten</em> (the
     * prior baseline and its snapshots are replaced) — this is the security AC's "écraser" action.
     * When {@code requestedIndex} is {@code null}, the lowest free slot (0..10) is auto-assigned; if
     * none is free the caller is refused (error AC).
     *
     * @param tenantId       the requesting tenant's {@code public.tenants.id}
     * @param teamId         the requesting team's {@code public.teams.id}
     * @param projectId      the project id
     * @param requestedIndex the target slot, or {@code null} to auto-assign
     * @return the posed baseline
     * @throws BaselineProjectNotFoundException if the project is not visible to the tenant/team
     * @throws InvalidBaselineIndexException    if {@code requestedIndex} is outside {@code 0..10}
     * @throws BaselineLimitExceededException   if the index is omitted and all 11 slots are used
     */
    @Transactional
    public BaselineResponse setBaseline(final long tenantId, final long teamId, final long projectId,
            final Short requestedIndex) {
        requireProject(tenantId, teamId, projectId);

        final short index = resolveTargetIndex(tenantId, teamId, projectId, requestedIndex);
        final Optional<Baseline> existing =
                baselineRepository.findByProjectIdAndTenantIdAndTeamIdAndBaselineIndex(
                        projectId, tenantId, teamId, index);
        existing.ifPresent(b -> {
            baselineRepository.delete(b);
            baselineRepository.flush();
        });

        final List<Task> tasks = taskRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId);
        final Map<Long, List<Assignment>> assignmentsByTask = loadAssignmentsByTask(tenantId, teamId, tasks);

        final Baseline baseline =
                baselineRepository.save(new Baseline(tenantId, teamId, projectId, index, Instant.now()));

        final List<BaselineSnapshot> snapshots = new ArrayList<>(tasks.size());
        for (final Task task : tasks) {
            final List<Assignment> assignments = assignmentsByTask.getOrDefault(task.getId(), List.of());
            final BaselineSnapshot snapshot =
                    new BaselineSnapshot(tenantId, teamId, baseline.getId(), task.getId());
            snapshot.setBlStart(task.getStartDate());
            snapshot.setBlFinish(task.getFinishDate());
            snapshot.setBlDurationMinutes(task.getDurationMinutes());
            snapshot.setBlWorkMinutes(sumWork(assignments));
            snapshot.setBlCostAmount(sumCost(assignments));
            snapshot.setBlTemporalPrecision(task.getTemporalPrecision());
            snapshots.add(snapshot);
        }
        if (!snapshots.isEmpty()) {
            snapshotRepository.saveAll(snapshots);
        }

        LOG.info("event=baseline_set tenant={} team={} project={} baselineIndex={} overwrite={} taskCount={}",
                tenantId, teamId, projectId, index, existing.isPresent(), tasks.size());

        return new BaselineResponse(baseline.getId(), baseline.getBaselineIndex(), baseline.getCapturedAt(),
                tasks.size());
    }

    /**
     * Deletes a baseline; its snapshots cascade via the DB FK ({@code ON DELETE CASCADE}).
     *
     * @param tenantId      the requesting tenant's {@code public.tenants.id}
     * @param teamId        the requesting team's {@code public.teams.id}
     * @param projectId     the project id
     * @param baselineIndex the baseline slot to delete
     * @throws BaselineProjectNotFoundException if the project is not visible to the tenant/team
     * @throws BaselineNotFoundException        if no baseline is set at that index
     */
    @Transactional
    public void deleteBaseline(final long tenantId, final long teamId, final long projectId,
            final short baselineIndex) {
        requireProject(tenantId, teamId, projectId);
        final Baseline baseline = requireBaseline(tenantId, teamId, projectId, baselineIndex);
        baselineRepository.delete(baseline);
        LOG.info("event=baseline_deleted tenant={} team={} project={} baselineIndex={}",
                tenantId, teamId, projectId, baselineIndex);
    }

    /**
     * Lists a project's baselines, ordered by slot.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @return the baselines (possibly empty)
     * @throws BaselineProjectNotFoundException if the project is not visible to the tenant/team
     */
    @Transactional(readOnly = true)
    public List<BaselineResponse> listBaselines(final long tenantId, final long teamId, final long projectId) {
        requireProject(tenantId, teamId, projectId);
        return baselineRepository.findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId).stream()
                .sorted(Comparator.comparing(Baseline::getBaselineIndex))
                .map(b -> new BaselineResponse(b.getId(), b.getBaselineIndex(), b.getCapturedAt(),
                        (int) snapshotRepository.countByBaselineIdAndTenantIdAndTeamId(b.getId(), tenantId, teamId)))
                .toList();
    }

    // ---- écarts (variance) -------------------------------------------------------------------------

    /**
     * Computes one baseline's per-task écarts against the current temporal graph — comparing the
     * frozen snapshot values to the live {@link Task}/{@link Assignment} rows, never recomputing the
     * baseline itself.
     *
     * @param tenantId      the requesting tenant's {@code public.tenants.id}
     * @param teamId        the requesting team's {@code public.teams.id}
     * @param projectId     the project id
     * @param baselineIndex the baseline slot to compare against
     * @return the variance report
     * @throws BaselineProjectNotFoundException if the project is not visible to the tenant/team
     * @throws BaselineNotFoundException        if no baseline is set at that index
     */
    @Transactional(readOnly = true)
    public BaselineVarianceResponse variance(final long tenantId, final long teamId, final long projectId,
            final short baselineIndex) {
        requireProject(tenantId, teamId, projectId);
        final Baseline baseline = requireBaseline(tenantId, teamId, projectId, baselineIndex);
        final List<BaselineSnapshot> snapshots =
                snapshotRepository.findAllByBaselineIdAndTenantIdAndTeamId(baseline.getId(), tenantId, teamId);

        final List<Task> currentTasks = taskRepository.findAllByProjectIdAndTenantIdAndTeamId(
                projectId, tenantId, teamId);
        final Map<Long, Task> currentById = currentTasks.stream()
                .collect(Collectors.toMap(Task::getId, t -> t));
        final Map<Long, List<Assignment>> assignmentsByTask = loadAssignmentsByTask(tenantId, teamId, currentTasks);

        final List<TaskVarianceResponse> rows = snapshots.stream()
                .sorted(Comparator.comparing(BaselineSnapshot::getTaskId))
                .map(s -> toVarianceRow(s, currentById.get(s.getTaskId()),
                        assignmentsByTask.getOrDefault(s.getTaskId(), List.of())))
                .toList();

        return new BaselineVarianceResponse(baseline.getBaselineIndex(), baseline.getCapturedAt(), rows);
    }

    /**
     * Compares two baselines directly — no "current" value involved, only the two frozen snapshots.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id}
     * @param teamId    the requesting team's {@code public.teams.id}
     * @param projectId the project id
     * @param fromIndex the earlier/reference baseline slot
     * @param toIndex   the later/compared-to baseline slot
     * @return the comparison report
     * @throws BaselineProjectNotFoundException if the project is not visible to the tenant/team
     * @throws BaselineNotFoundException        if either index has no baseline set
     */
    @Transactional(readOnly = true)
    public BaselineComparisonResponse compare(final long tenantId, final long teamId, final long projectId,
            final short fromIndex, final short toIndex) {
        requireProject(tenantId, teamId, projectId);
        final Baseline from = requireBaseline(tenantId, teamId, projectId, fromIndex);
        final Baseline to = requireBaseline(tenantId, teamId, projectId, toIndex);

        final Map<Long, BaselineSnapshot> fromByTask =
                snapshotRepository.findAllByBaselineIdAndTenantIdAndTeamId(from.getId(), tenantId, teamId).stream()
                        .collect(Collectors.toMap(BaselineSnapshot::getTaskId, s -> s));
        final Map<Long, BaselineSnapshot> toByTask =
                snapshotRepository.findAllByBaselineIdAndTenantIdAndTeamId(to.getId(), tenantId, teamId).stream()
                        .collect(Collectors.toMap(BaselineSnapshot::getTaskId, s -> s));

        final Set<Long> taskIds = new TreeSet<>();
        taskIds.addAll(fromByTask.keySet());
        taskIds.addAll(toByTask.keySet());

        final Map<Long, Task> currentById = taskRepository
                .findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId).stream()
                .collect(Collectors.toMap(Task::getId, t -> t));

        final List<BaselineComparisonRowResponse> rows = taskIds.stream()
                .map(id -> toComparisonRow(id, fromByTask.get(id), toByTask.get(id), currentById.get(id)))
                .toList();

        return new BaselineComparisonResponse(from.getBaselineIndex(), from.getCapturedAt(),
                to.getBaselineIndex(), to.getCapturedAt(), rows);
    }

    // ---- shared guards ------------------------------------------------------------------------------

    private Project requireProject(final long tenantId, final long teamId, final long projectId) {
        return projectRepository.findByIdAndTenantIdAndTeamId(projectId, tenantId, teamId)
                .orElseThrow(() -> new BaselineProjectNotFoundException(projectId, tenantId, teamId));
    }

    private Baseline requireBaseline(final long tenantId, final long teamId, final long projectId,
            final short baselineIndex) {
        return baselineRepository
                .findByProjectIdAndTenantIdAndTeamIdAndBaselineIndex(projectId, tenantId, teamId, baselineIndex)
                .orElseThrow(() -> new BaselineNotFoundException(projectId, baselineIndex));
    }

    /** Resolves the slot to pose into: the explicit, range-validated index, or the lowest free one. */
    private short resolveTargetIndex(final long tenantId, final long teamId, final long projectId,
            final Short requestedIndex) {
        if (requestedIndex != null) {
            if (requestedIndex < MIN_INDEX || requestedIndex > MAX_INDEX) {
                throw InvalidBaselineIndexException.outOfRange(requestedIndex);
            }
            return requestedIndex;
        }
        final Set<Short> used = baselineRepository
                .findAllByProjectIdAndTenantIdAndTeamId(projectId, tenantId, teamId).stream()
                .map(Baseline::getBaselineIndex)
                .collect(Collectors.toSet());
        for (short i = MIN_INDEX; i <= MAX_INDEX; i++) {
            if (!used.contains(i)) {
                return i;
            }
        }
        throw new BaselineLimitExceededException(projectId);
    }

    // ---- assignment aggregation ----------------------------------------------------------------

    private Map<Long, List<Assignment>> loadAssignmentsByTask(final long tenantId, final long teamId,
            final List<Task> tasks) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        final List<Long> taskIds = tasks.stream().map(Task::getId).toList();
        return assignmentRepository.findAllByTaskIdInAndTenantIdAndTeamId(taskIds, tenantId, teamId).stream()
                .collect(Collectors.groupingBy(Assignment::getTaskId));
    }

    /** Sums assignment work, treating a missing value as zero; {@code null} only when unassigned. */
    private static Integer sumWork(final List<Assignment> assignments) {
        if (assignments.isEmpty()) {
            return null;
        }
        long sum = 0L;
        for (final Assignment a : assignments) {
            sum += a.getWorkMinutes() != null ? a.getWorkMinutes() : Integer.valueOf(0);
        }
        return Integer.valueOf((int) sum);
    }

    /** Sums assignment cost; {@code null} when unassigned or no assignment carries a cost. */
    private static BigDecimal sumCost(final List<Assignment> assignments) {
        BigDecimal sum = null;
        for (final Assignment a : assignments) {
            if (a.getCostAmount() != null) {
                sum = sum == null ? a.getCostAmount() : sum.add(a.getCostAmount());
            }
        }
        return sum;
    }

    // ---- variance row mapping -------------------------------------------------------------------

    private TaskVarianceResponse toVarianceRow(final BaselineSnapshot snapshot, final Task current,
            final List<Assignment> currentAssignments) {
        final String taskName = current != null ? current.getName() : null;

        final Instant blStart = snapshot.getBlStart();
        final Instant curStart = current != null ? current.getStartDate() : null;
        final Long startVarianceMinutes = minutesBetween(blStart, curStart);

        final Instant blFinish = snapshot.getBlFinish();
        final Instant curFinish = current != null ? current.getFinishDate() : null;
        final Long finishVarianceMinutes = minutesBetween(blFinish, curFinish);

        final Integer blDuration = snapshot.getBlDurationMinutes();
        final Integer curDuration = current != null ? current.getDurationMinutes() : null;
        final Integer durationVarianceMinutes = intDelta(blDuration, curDuration);
        final BigDecimal durationVariancePercent = percent(durationVarianceMinutes, blDuration);

        final Integer blWork = snapshot.getBlWorkMinutes();
        final Integer curWork = current != null ? sumWork(currentAssignments) : null;
        final Integer workVarianceMinutes = intDelta(blWork, curWork);
        final BigDecimal workVariancePercent = percent(workVarianceMinutes, blWork);

        final BigDecimal blCost = snapshot.getBlCostAmount();
        final BigDecimal curCost = current != null ? sumCost(currentAssignments) : null;
        final BigDecimal costVarianceAmount = decimalDelta(blCost, curCost);
        final BigDecimal costVariancePercent = percent(costVarianceAmount, blCost);

        final TemporalPrecision blPrecision = snapshot.getBlTemporalPrecision();
        final TemporalPrecision curPrecision = current != null ? current.getTemporalPrecision() : null;
        final boolean precisionChanged = blPrecision != null && curPrecision != null && blPrecision != curPrecision;

        return new TaskVarianceResponse(snapshot.getTaskId(), taskName,
                blStart, curStart, startVarianceMinutes, dayVarianceLabel("Début", startVarianceMinutes),
                blFinish, curFinish, finishVarianceMinutes, dayVarianceLabel("Fin", finishVarianceMinutes),
                blDuration, curDuration, durationVarianceMinutes, durationVariancePercent,
                percentVarianceLabel("Durée", durationVariancePercent),
                blWork, curWork, workVarianceMinutes, workVariancePercent,
                percentVarianceLabel("Travail", workVariancePercent),
                blCost, curCost, costVarianceAmount, costVariancePercent,
                costVarianceLabel(costVarianceAmount, costVariancePercent),
                blPrecision, curPrecision, precisionChanged);
    }

    private BaselineComparisonRowResponse toComparisonRow(final long taskId, final BaselineSnapshot from,
            final BaselineSnapshot to, final Task current) {
        final String taskName = current != null ? current.getName() : null;

        final Instant fromStart = from != null ? from.getBlStart() : null;
        final Instant toStart = to != null ? to.getBlStart() : null;
        final Long startDeltaMinutes = minutesBetween(fromStart, toStart);

        final Instant fromFinish = from != null ? from.getBlFinish() : null;
        final Instant toFinish = to != null ? to.getBlFinish() : null;
        final Long finishDeltaMinutes = minutesBetween(fromFinish, toFinish);

        final Integer fromDuration = from != null ? from.getBlDurationMinutes() : null;
        final Integer toDuration = to != null ? to.getBlDurationMinutes() : null;
        final Integer durationDeltaMinutes = intDelta(fromDuration, toDuration);
        final BigDecimal durationDeltaPercent = percent(durationDeltaMinutes, fromDuration);

        final Integer fromWork = from != null ? from.getBlWorkMinutes() : null;
        final Integer toWork = to != null ? to.getBlWorkMinutes() : null;
        final Integer workDeltaMinutes = intDelta(fromWork, toWork);
        final BigDecimal workDeltaPercent = percent(workDeltaMinutes, fromWork);

        final BigDecimal fromCost = from != null ? from.getBlCostAmount() : null;
        final BigDecimal toCost = to != null ? to.getBlCostAmount() : null;
        final BigDecimal costDeltaAmount = decimalDelta(fromCost, toCost);
        final BigDecimal costDeltaPercent = percent(costDeltaAmount, fromCost);

        return new BaselineComparisonRowResponse(taskId, taskName,
                fromStart, toStart, startDeltaMinutes, dayVarianceLabel("Début", startDeltaMinutes),
                fromFinish, toFinish, finishDeltaMinutes, dayVarianceLabel("Fin", finishDeltaMinutes),
                fromDuration, toDuration, durationDeltaMinutes, durationDeltaPercent,
                percentVarianceLabel("Durée", durationDeltaPercent),
                fromWork, toWork, workDeltaMinutes, workDeltaPercent,
                percentVarianceLabel("Travail", workDeltaPercent),
                fromCost, toCost, costDeltaAmount, costDeltaPercent,
                costVarianceLabel(costDeltaAmount, costDeltaPercent));
    }

    // ---- delta / percentage arithmetic -----------------------------------------------------------

    private static Long minutesBetween(final Instant reference, final Instant compared) {
        if (reference == null || compared == null) {
            return null;
        }
        return Duration.between(reference, compared).toMinutes();
    }

    private static Integer intDelta(final Integer reference, final Integer compared) {
        if (reference == null || compared == null) {
            return null;
        }
        return compared - reference;
    }

    private static BigDecimal decimalDelta(final BigDecimal reference, final BigDecimal compared) {
        if (reference == null || compared == null) {
            return null;
        }
        return compared.subtract(reference);
    }

    private static BigDecimal percent(final Integer delta, final Integer base) {
        if (delta == null || base == null || base == 0) {
            return null;
        }
        return BigDecimal.valueOf(delta).multiply(PERCENT)
                .divide(BigDecimal.valueOf(base), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(final BigDecimal delta, final BigDecimal base) {
        if (delta == null || base == null || base.signum() == 0) {
            return null;
        }
        return delta.multiply(PERCENT).divide(base, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    // ---- A11y textual labels (never colour/sign alone) ---------------------------------------------

    /** Renders a minute-granularity date variance as a day-level, colour-independent label. */
    private static String dayVarianceLabel(final String metric, final Long varianceMinutes) {
        if (varianceMinutes == null) {
            return metric + " : non comparable (donnée absente)";
        }
        if (varianceMinutes == 0L) {
            return metric + " sans écart";
        }
        final long days = Math.round(varianceMinutes / (double) MINUTES_PER_DAY);
        if (days == 0L) {
            return varianceMinutes > 0
                    ? metric + " en léger retard (< 1 j)"
                    : metric + " en légère avance (< 1 j)";
        }
        return varianceMinutes > 0
                ? metric + " en retard de " + days + " j"
                : metric + " en avance de " + Math.abs(days) + " j";
    }

    /** Renders a percentage variance as a colour-independent label. */
    private static String percentVarianceLabel(final String metric, final BigDecimal variancePercent) {
        if (variancePercent == null) {
            return metric + " : non comparable (donnée absente)";
        }
        if (variancePercent.signum() == 0) {
            return metric + " sans écart";
        }
        return variancePercent.signum() > 0
                ? metric + " en hausse de " + variancePercent.abs() + " %"
                : metric + " en baisse de " + variancePercent.abs() + " %";
    }

    /** Renders a cost variance (amount + optional percent) as a colour-independent label. */
    private static String costVarianceLabel(final BigDecimal varianceAmount, final BigDecimal variancePercent) {
        if (varianceAmount == null) {
            return "Coût : non comparable (donnée absente)";
        }
        if (varianceAmount.signum() == 0) {
            return "Coût sans écart";
        }
        final String pctSuffix = variancePercent != null
                ? " (" + (variancePercent.signum() > 0 ? "+" : "") + variancePercent + " %)"
                : "";
        return varianceAmount.signum() > 0
                ? "Coût en dépassement de " + varianceAmount.abs() + pctSuffix
                : "Coût en économie de " + varianceAmount.abs() + pctSuffix;
    }
}

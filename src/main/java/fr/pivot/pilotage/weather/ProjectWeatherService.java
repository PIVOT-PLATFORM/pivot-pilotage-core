package fr.pivot.pilotage.weather;

import fr.pivot.pilotage.consolidation.ApplicationNotFoundException;
import fr.pivot.pilotage.consolidation.ProjectNotFoundException;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped computation of the normalized project "weather" indicator (US23.2.4) — a single,
 * reusable source of truth so US23.2.1 (consolidated portfolio view) and US23.2.2 (customizable
 * dashboards) never recompute a divergent variant of the same signal (backlog note: "le calcul
 * doit être exposé via une API/entité réutilisable plutôt que dupliqué dans chaque vue").
 *
 * <p><strong>Homogeneous, fixed rules.</strong> The classification thresholds below are the same
 * for every project regardless of organizational profile (PME, Grand groupe, Publique…) and are
 * not customizable per tenant/organization for this US (backlog "Hors périmètre"). Their
 * <em>modification</em> is nonetheless role-gated — see {@link WeatherRuleAuthorization}.
 *
 * <p><strong>Calculation model.</strong> For a project with a {@code statusDate} (EN22.1a
 * freshness anchor) and a derivable temporal window (earliest task start .. latest task finish,
 * fuzzy-period-first then precise-date fallback — mirrors {@code
 * fr.pivot.pilotage.consolidation.ApplicationConsolidationService}):
 * <ul>
 *   <li><strong>actual progress</strong> = average {@code task_progress.percent_complete} across
 *       the project's {@link NodeKind#LEAF} tasks that carry a progress record (mirrors the
 *       leaf-percent pattern already used by {@code fr.pivot.pilotage.gantt.WbsTaskService});</li>
 *   <li><strong>expected progress</strong> = the homogeneous linear share of the temporal window
 *       elapsed at {@code statusDate} (0–100, clamped);</li>
 *   <li><strong>variance</strong> = actual − expected, in percentage points.</li>
 * </ul>
 * Variance ≥ {@link #ON_TRACK_VARIANCE_THRESHOLD} → {@link ProjectWeatherStatus#SUNNY}; down to
 * {@link #AT_RISK_VARIANCE_THRESHOLD} → {@link ProjectWeatherStatus#CLOUDY}; below → {@link
 * ProjectWeatherStatus#STORMY}.
 *
 * <p><strong>Error case.</strong> Missing {@code statusDate}, an unusable window, or no leaf
 * progress record at all → {@link ProjectWeatherStatus#INDETERMINATE} with an explicit {@link
 * ProjectWeatherIndeterminateReason} — never a misleading default weather.
 *
 * <p><strong>Security.</strong> Every read goes through a tenant-scoped repository; an unknown or
 * cross-tenant project/application is treated as absent ({@link ProjectNotFoundException} /
 * {@link ApplicationNotFoundException}, both 404-equivalent, reused from the sibling {@code
 * consolidation} package — EN18.9 foundational infrastructure, not in-flight US work). No
 * inter-module FK is traversed.
 */
@Service
public class ProjectWeatherService {

    /**
     * Variance (points) at or above which a project is {@link ProjectWeatherStatus#SUNNY}. Fixed,
     * homogeneous — see class Javadoc.
     */
    static final BigDecimal ON_TRACK_VARIANCE_THRESHOLD = new BigDecimal("-5");

    /**
     * Variance (points) at or above which (but below {@link #ON_TRACK_VARIANCE_THRESHOLD}) a
     * project is {@link ProjectWeatherStatus#CLOUDY}; below it, {@link
     * ProjectWeatherStatus#STORMY}. Fixed, homogeneous — see class Javadoc.
     */
    static final BigDecimal AT_RISK_VARIANCE_THRESHOLD = new BigDecimal("-15");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PERCENT_SCALE = 2;

    private final ProjectRepository projectRepository;
    private final ApplicationRepository applicationRepository;
    private final TaskRepository taskRepository;
    private final TaskProgressRepository taskProgressRepository;

    /**
     * Constructs the weather computation service.
     *
     * @param projectRepository      tenant-scoped project repository (EN18.1)
     * @param applicationRepository  tenant-scoped application repository (EN18.1), used only to
     *                               reject an unknown/cross-tenant application in the batch path
     * @param taskRepository         tenant-scoped task (temporal graph) repository (EN22.1)
     * @param taskProgressRepository tenant-scoped task progress repository (EN22.1a)
     */
    public ProjectWeatherService(final ProjectRepository projectRepository,
            final ApplicationRepository applicationRepository, final TaskRepository taskRepository,
            final TaskProgressRepository taskProgressRepository) {
        this.projectRepository = projectRepository;
        this.applicationRepository = applicationRepository;
        this.taskRepository = taskRepository;
        this.taskProgressRepository = taskProgressRepository;
    }

    /**
     * Computes the normalized weather of a single project.
     *
     * @param tenantId  the requesting tenant's {@code public.tenants.id} (isolation boundary)
     * @param projectId the project to evaluate
     * @return the {@link ProjectWeather}
     * @throws ProjectNotFoundException if the project does not exist or is not visible to the
     *                                  tenant (cross-tenant access is treated as absent — 404
     *                                  equivalent)
     */
    @Transactional(readOnly = true)
    public ProjectWeather computeWeather(final long tenantId, final long projectId) {
        final Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, tenantId));
        return computeWeather(tenantId, project);
    }

    /**
     * Computes the normalized weather of every project of one application, within the tenant
     * boundary — the batch path US23.2.1's consolidated portfolio view is meant to call so each
     * project's indicator is computed exactly once and merely surfaced, never recalculated
     * divergently per view.
     *
     * @param tenantId      the requesting tenant's {@code public.tenants.id} (isolation boundary)
     * @param applicationId the application whose projects are evaluated
     * @return the projects' {@link ProjectWeather}, ordered by project id (deterministic)
     * @throws ApplicationNotFoundException if the application does not exist or is not visible to
     *                                      the tenant (cross-tenant access is treated as absent —
     *                                      404 equivalent)
     */
    @Transactional(readOnly = true)
    public List<ProjectWeather> computeWeatherForApplication(final long tenantId, final long applicationId) {
        applicationRepository.findByIdAndTenantId(applicationId, tenantId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId, tenantId));

        final List<Project> projects =
                projectRepository.findAllByApplicationIdAndTenantId(applicationId, tenantId);
        final List<ProjectWeather> weathers = new ArrayList<>();
        for (final Project project : projects) {
            weathers.add(computeWeather(tenantId, project));
        }
        weathers.sort(Comparator.comparingLong(ProjectWeather::projectId));
        return weathers;
    }

    // ---- calculation --------------------------------------------------------------------------

    private ProjectWeather computeWeather(final long tenantId, final Project project) {
        final long projectId = project.getId();
        final LocalDate asOfDate = project.getStatusDate();
        if (asOfDate == null) {
            return indeterminate(projectId, tenantId, null, ProjectWeatherIndeterminateReason.MISSING_STATUS_DATE);
        }

        final List<Task> tasks = taskRepository.findAllByProjectIdAndTenantId(projectId, tenantId);
        final LocalDate windowStart = windowStart(tasks);
        final LocalDate windowFinish = windowFinish(tasks);
        if (windowStart == null || windowFinish == null || windowFinish.isBefore(windowStart)) {
            return indeterminate(projectId, tenantId, asOfDate, ProjectWeatherIndeterminateReason.MISSING_WINDOW);
        }

        final BigDecimal actualProgress = averageLeafProgress(tenantId, tasks);
        if (actualProgress == null) {
            return indeterminate(projectId, tenantId, asOfDate, ProjectWeatherIndeterminateReason.MISSING_PROGRESS);
        }

        final BigDecimal expectedProgress = expectedProgress(windowStart, windowFinish, asOfDate);
        final BigDecimal variance = actualProgress.subtract(expectedProgress);
        final ProjectWeatherStatus status = statusOf(variance);

        return new ProjectWeather(projectId, tenantId, status, actualProgress, expectedProgress, variance,
                asOfDate, null);
    }

    private static ProjectWeather indeterminate(final long projectId, final long tenantId,
            final LocalDate asOfDate, final ProjectWeatherIndeterminateReason reason) {
        return new ProjectWeather(projectId, tenantId, ProjectWeatherStatus.INDETERMINATE, null, null, null,
                asOfDate, reason);
    }

    private static ProjectWeatherStatus statusOf(final BigDecimal varianceInPoints) {
        if (varianceInPoints.compareTo(ON_TRACK_VARIANCE_THRESHOLD) >= 0) {
            return ProjectWeatherStatus.SUNNY;
        }
        if (varianceInPoints.compareTo(AT_RISK_VARIANCE_THRESHOLD) >= 0) {
            return ProjectWeatherStatus.CLOUDY;
        }
        return ProjectWeatherStatus.STORMY;
    }

    /**
     * Average {@code percent_complete} across the project's leaf tasks carrying a progress
     * record, or {@code null} if none does (mirrors the leaf-percent pattern in {@code
     * fr.pivot.pilotage.gantt.WbsTaskService#leafPercents}).
     */
    private BigDecimal averageLeafProgress(final long tenantId, final List<Task> tasks) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (final Task task : tasks) {
            if (task.getNodeKind() != NodeKind.LEAF) {
                continue;
            }
            final TaskProgress progress =
                    taskProgressRepository.findByTaskIdAndTenantId(task.getId(), tenantId).orElse(null);
            if (progress != null && progress.getPercentComplete() != null) {
                sum = sum.add(progress.getPercentComplete());
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(count), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Homogeneous linear share (0–100, clamped) of the window elapsed at {@code asOfDate}. A
     * single-day window (start == finish) resolves to 0 before it and 100 from it onward.
     */
    private static BigDecimal expectedProgress(final LocalDate windowStart, final LocalDate windowFinish,
            final LocalDate asOfDate) {
        final long totalDays = ChronoUnit.DAYS.between(windowStart, windowFinish);
        if (totalDays <= 0) {
            return asOfDate.isBefore(windowFinish) ? BigDecimal.ZERO : HUNDRED;
        }
        final long rawElapsed = ChronoUnit.DAYS.between(windowStart, asOfDate);
        final long clampedElapsed = Math.max(0, Math.min(totalDays, rawElapsed));
        return BigDecimal.valueOf(clampedElapsed).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(totalDays), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /** Earliest task start at the roadmap altitude — fuzzy lower bound first, else precise date. */
    private static LocalDate windowStart(final List<Task> tasks) {
        LocalDate earliest = null;
        for (final Task task : tasks) {
            final LocalDate start = startOf(task);
            if (start != null && (earliest == null || start.isBefore(earliest))) {
                earliest = start;
            }
        }
        return earliest;
    }

    /** Latest task finish at the roadmap altitude — fuzzy upper bound first, else precise date. */
    private static LocalDate windowFinish(final List<Task> tasks) {
        LocalDate latest = null;
        for (final Task task : tasks) {
            final LocalDate finish = finishOf(task);
            if (finish != null && (latest == null || finish.isAfter(latest))) {
                latest = finish;
            }
        }
        return latest;
    }

    private static LocalDate startOf(final Task task) {
        if (task.getFuzzyPeriodStart() != null) {
            return task.getFuzzyPeriodStart();
        }
        return task.getStartDate() == null ? null : task.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate finishOf(final Task task) {
        if (task.getFuzzyPeriodEnd() != null) {
            return task.getFuzzyPeriodEnd();
        }
        return task.getFinishDate() == null ? null : task.getFinishDate().atZone(ZoneOffset.UTC).toLocalDate();
    }
}

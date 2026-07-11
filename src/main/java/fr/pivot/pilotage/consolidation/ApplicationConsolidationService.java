package fr.pivot.pilotage.consolidation;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskRepository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped "consolidation by Application" contract (EN18.9) — the frozen Gate 1 decision limits
 * this enabler to <strong>aggregation</strong> at the application level; it recreates none of the
 * EN18.1 entities/FK/invariant/schema.
 *
 * <p>{@link #consolidate(long, long)} rolls up, for one application, the data of all its projects:
 * <ul>
 *   <li><strong>Pilotage-owned aggregate</strong> (read from the persisted temporal graph EN22.1
 *       via the existing tenant-scoped repositories — never a second store): number of projects,
 *       projects per derived {@link ProjectPlanningStatus}, the global temporal window (earliest
 *       start / latest finish across projects) and the union of the projects' {@code
 *       shared_in_roadmap} strategic milestones.</li>
 *   <li><strong>Cross-module aggregate</strong> (ADR-006/ADR-008): data owned by other modules
 *       (budget E26, risks E21, decisions/ADR E24…) is <em>not</em> reachable by an inter-module FK;
 *       every registered {@link ApplicationDataContributor} is invoked so those modules can push
 *       their per-application aggregate over the PIVOT bus. With no bus wired yet (documented gap),
 *       only the {@link NoOpApplicationDataContributor} is present and the cross-module part is
 *       empty — the pilotage aggregate stands alone.</li>
 * </ul>
 *
 * <p><strong>No inter-module FK traversed.</strong> The service reads only the {@code pilotage}
 * schema (application, project, task) through repositories this module owns; anything else arrives
 * through the SPI. <strong>REST deferred</strong> (CLAUDE.md §gap, TODO-SETUP §5): {@code tenantId}
 * and {@code applicationId} are explicit arguments, never taken from a body/param/header; a
 * cross-tenant or unknown application yields {@link ApplicationNotFoundException} (404 equivalent).
 */
@Service
public class ApplicationConsolidationService {

    private final ApplicationRepository applicationRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final List<ApplicationDataContributor> contributors;

    /**
     * Constructs the consolidation service.
     *
     * @param applicationRepository tenant-scoped application repository (EN18.1)
     * @param projectRepository     tenant-scoped project repository (EN18.1)
     * @param taskRepository        tenant-scoped task (temporal graph) repository (EN22.1)
     * @param contributors          all cross-module contributors on the classpath (at least the
     *                              no-op default); the bus-backed ones plug in post-starter
     */
    public ApplicationConsolidationService(final ApplicationRepository applicationRepository,
            final ProjectRepository projectRepository, final TaskRepository taskRepository,
            final List<ApplicationDataContributor> contributors) {
        this.applicationRepository = applicationRepository;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.contributors = List.copyOf(contributors);
    }

    /**
     * Consolidates one application within the tenant boundary.
     *
     * @param tenantId      the requesting tenant's {@code public.tenants.id} (isolation boundary)
     * @param applicationId the application to consolidate
     * @return the immutable {@link ApplicationConsolidation}
     * @throws ApplicationNotFoundException if the application does not exist or is not visible to the
     *                                      tenant (cross-tenant access is treated as absent — 404
     *                                      equivalent)
     */
    @Transactional(readOnly = true)
    public ApplicationConsolidation consolidate(final long tenantId, final long applicationId) {
        final Application application = applicationRepository
                .findByIdAndTenantId(applicationId, tenantId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId, tenantId));

        final List<Project> projects = projectRepository
                .findAllByApplicationIdAndTenantId(applicationId, tenantId);

        final Map<ProjectPlanningStatus, Integer> byStatus =
                new EnumMap<>(ProjectPlanningStatus.class);
        final List<ApplicationMilestone> milestones = new ArrayList<>();
        LocalDate windowStart = null;
        LocalDate windowFinish = null;

        for (final Project project : projects) {
            final List<Task> tasks = taskRepository
                    .findAllByProjectIdAndTenantId(project.getId(), tenantId);
            increment(byStatus, planningStatusOf(tasks));

            for (final Task task : tasks) {
                final LocalDate start = startOf(task);
                final LocalDate finish = finishOf(task);
                if (start != null && (windowStart == null || start.isBefore(windowStart))) {
                    windowStart = start;
                }
                if (finish != null && (windowFinish == null || finish.isAfter(windowFinish))) {
                    windowFinish = finish;
                }
                if (isStrategicMilestone(task)) {
                    milestones.add(new ApplicationMilestone(task.getId(), project.getId(),
                            task.getName(), task.getFuzzyPeriodStart(), task.getFuzzyPeriodEnd()));
                }
            }
        }

        milestones.sort(Comparator.comparingLong(ApplicationMilestone::projectId)
                .thenComparingLong(ApplicationMilestone::nodeId));

        final List<ApplicationAggregateContribution> contributions =
                collectContributions(tenantId, applicationId);

        return new ApplicationConsolidation(applicationId, application.getName(), tenantId,
                projects.size(), byStatus, windowStart, windowFinish, milestones, contributions);
    }

    // ---- cross-module SPI (bus seam) ----------------------------------------------------------

    /**
     * Invokes every registered {@link ApplicationDataContributor} and collects the non-empty
     * aggregates, ordered by module id for a deterministic result. With only the no-op default
     * wired, the list is empty.
     */
    private List<ApplicationAggregateContribution> collectContributions(final long tenantId,
            final long applicationId) {
        final List<ApplicationAggregateContribution> collected = new ArrayList<>();
        for (final ApplicationDataContributor contributor : contributors) {
            final Optional<ApplicationAggregateContribution> contribution =
                    contributor.contribute(tenantId, applicationId);
            contribution.ifPresent(collected::add);
        }
        collected.sort(Comparator.comparing(ApplicationAggregateContribution::moduleId));
        return collected;
    }

    // ---- pilotage-owned derivations -----------------------------------------------------------

    private static void increment(final Map<ProjectPlanningStatus, Integer> byStatus,
            final ProjectPlanningStatus status) {
        byStatus.merge(status, 1, Integer::sum);
    }

    /**
     * Derives a project's {@link ProjectPlanningStatus} from the temporal graph the pilotage domain
     * owns (no persisted lifecycle column exists — EN18.1 defined none, EN18.9 adds none): no task
     * → {@code EMPTY}; at least one task with a precise start/finish window → {@code SCHEDULED};
     * otherwise → {@code PLANNED}.
     */
    private static ProjectPlanningStatus planningStatusOf(final List<Task> tasks) {
        if (tasks.isEmpty()) {
            return ProjectPlanningStatus.EMPTY;
        }
        for (final Task task : tasks) {
            if (task.getStartDate() != null || task.getFinishDate() != null) {
                return ProjectPlanningStatus.SCHEDULED;
            }
        }
        return ProjectPlanningStatus.PLANNED;
    }

    /**
     * A strategic milestone at the application level is a {@code MILESTONE} node flagged {@code
     * shared_in_roadmap} — the roadmap marker unified across the application's projects.
     */
    private static boolean isStrategicMilestone(final Task task) {
        return task.getNodeKind() == NodeKind.MILESTONE
                && Boolean.TRUE.equals(task.getSharedInRoadmap());
    }

    /**
     * Task start at the application (roadmap) altitude: the fuzzy-period lower bound when set, else
     * the precise start date folded to a UTC calendar day, else {@code null}.
     */
    private static LocalDate startOf(final Task task) {
        if (task.getFuzzyPeriodStart() != null) {
            return task.getFuzzyPeriodStart();
        }
        return task.getStartDate() == null ? null
                : task.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Task finish at the application (roadmap) altitude: the fuzzy-period upper bound when set, else
     * the precise finish date folded to a UTC calendar day, else {@code null}.
     */
    private static LocalDate finishOf(final Task task) {
        if (task.getFuzzyPeriodEnd() != null) {
            return task.getFuzzyPeriodEnd();
        }
        return task.getFinishDate() == null ? null
                : task.getFinishDate().atZone(ZoneOffset.UTC).toLocalDate();
    }
}

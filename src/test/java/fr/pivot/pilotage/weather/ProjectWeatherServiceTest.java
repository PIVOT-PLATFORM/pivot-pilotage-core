package fr.pivot.pilotage.weather;

import fr.pivot.pilotage.consolidation.ApplicationNotFoundException;
import fr.pivot.pilotage.consolidation.ProjectNotFoundException;
import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.ApplicationRepository;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.project.ProjectRepository;
import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TaskProgress;
import fr.pivot.pilotage.schedule.TaskProgressRepository;
import fr.pivot.pilotage.schedule.TaskRepository;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectWeatherService} with mocked repositories (US23.2.4). Cover the
 * homogeneous SUNNY/CLOUDY/STORMY classification, every INDETERMINATE error branch (missing
 * status date, missing/inconsistent window, missing progress), the batch application-scoped path
 * (no divergent recomputation) and tenant isolation (404-equivalent on both the single and batch
 * paths). Timestamps are anchored in the past, never {@code now()}.
 */
@ExtendWith(MockitoExtension.class)
class ProjectWeatherServiceTest {

    private static final long TENANT = 7L;
    private static final long TEAM = 5L;
    private static final long APP = 42L;
    private static final Instant ANCHOR = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    @Mock private ProjectRepository projectRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskProgressRepository taskProgressRepository;

    private static void setId(final Object entity, final long id) {
        try {
            final Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private ProjectWeatherService service() {
        return new ProjectWeatherService(projectRepository, applicationRepository, taskRepository,
                taskProgressRepository);
    }

    private Application application() {
        final Application app = new Application(TENANT, TEAM, "Billing", ANCHOR);
        setId(app, APP);
        return app;
    }

    private Project project(final long id, final LocalDate statusDate) {
        final Application app = application();
        final Project p = new Project(app, TENANT, TEAM, "v" + id, ANCHOR);
        setId(p, id);
        p.setStatusDate(statusDate);
        return p;
    }

    private Task leafWithWindow(final long id, final LocalDate start, final LocalDate end) {
        final Task t = new Task(TENANT, TEAM, 0L, 0, "leaf", NodeKind.LEAF, false, TemporalPrecision.DAY, 0);
        setId(t, id);
        t.setFuzzyPeriodStart(start);
        t.setFuzzyPeriodEnd(end);
        return t;
    }

    private void stubProgress(final long taskId, final BigDecimal percent) {
        final TaskProgress progress = new TaskProgress(TENANT, TEAM, taskId, percent);
        when(taskProgressRepository.findByTaskIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(progress));
    }

    // -------- AC1: homogeneous rules, complete data -----------------------------------------

    @Test
    void computeWeather_onTrack_yieldsSunny() {
        final Project p = project(100L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(100L, TENANT)).thenReturn(Optional.of(p));
        final Task task = leafWithWindow(1L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(100L, TENANT)).thenReturn(List.of(task));
        stubProgress(1L, new BigDecimal("50.00"));

        final ProjectWeather weather = service().computeWeather(TENANT, 100L);

        assertThat(weather.projectId()).isEqualTo(100L);
        assertThat(weather.tenantId()).isEqualTo(TENANT);
        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.SUNNY);
        assertThat(weather.actualProgressPercent()).isEqualByComparingTo("50.00");
        assertThat(weather.expectedProgressPercent()).isEqualByComparingTo("50.00");
        assertThat(weather.varianceInPoints()).isEqualByComparingTo("0.00");
        assertThat(weather.asOfDate()).isEqualTo(LocalDate.of(2024, 1, 6));
        assertThat(weather.indeterminateReason()).isNull();
    }

    @Test
    void computeWeather_mildlyBehind_yieldsCloudy() {
        final Project p = project(101L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(101L, TENANT)).thenReturn(Optional.of(p));
        final Task task = leafWithWindow(2L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(101L, TENANT)).thenReturn(List.of(task));
        stubProgress(2L, new BigDecimal("40.00"));

        final ProjectWeather weather = service().computeWeather(TENANT, 101L);

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.CLOUDY);
        assertThat(weather.varianceInPoints()).isEqualByComparingTo("-10.00");
    }

    @Test
    void computeWeather_severelyBehind_yieldsStormy() {
        final Project p = project(102L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(102L, TENANT)).thenReturn(Optional.of(p));
        final Task task = leafWithWindow(3L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(102L, TENANT)).thenReturn(List.of(task));
        stubProgress(3L, new BigDecimal("20.00"));

        final ProjectWeather weather = service().computeWeather(TENANT, 102L);

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.STORMY);
        assertThat(weather.varianceInPoints()).isEqualByComparingTo("-30.00");
    }

    @Test
    void computeWeather_aheadOfSchedule_yieldsSunny() {
        final Project p = project(103L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(103L, TENANT)).thenReturn(Optional.of(p));
        final Task task = leafWithWindow(4L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(103L, TENANT)).thenReturn(List.of(task));
        stubProgress(4L, new BigDecimal("90.00"));

        final ProjectWeather weather = service().computeWeather(TENANT, 103L);

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.SUNNY);
        assertThat(weather.varianceInPoints()).isEqualByComparingTo("40.00");
    }

    @Test
    void computeWeather_statusDateAtOrAfterFinish_expectedProgressClampedAt100() {
        final Project p = project(104L, LocalDate.of(2024, 2, 1));
        when(projectRepository.findByIdAndTenantId(104L, TENANT)).thenReturn(Optional.of(p));
        final Task task = leafWithWindow(5L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(104L, TENANT)).thenReturn(List.of(task));
        stubProgress(5L, new BigDecimal("100.00"));

        final ProjectWeather weather = service().computeWeather(TENANT, 104L);

        assertThat(weather.expectedProgressPercent()).isEqualByComparingTo("100.00");
        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.SUNNY);
    }

    @Test
    void computeWeather_singleDayWindowBeforeFinish_expectedProgressIsZero() {
        final Project p = project(105L, LocalDate.of(2024, 1, 1));
        when(projectRepository.findByIdAndTenantId(105L, TENANT)).thenReturn(Optional.of(p));
        // start == end (milestone-only window)
        final Task task = leafWithWindow(6L, LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 5));
        when(taskRepository.findAllByProjectIdAndTenantId(105L, TENANT)).thenReturn(List.of(task));
        stubProgress(6L, new BigDecimal("0.00"));

        final ProjectWeather weather = service().computeWeather(TENANT, 105L);

        assertThat(weather.expectedProgressPercent()).isEqualByComparingTo("0.00");
    }

    // -------- Error: missing data → INDETERMINATE, never a misleading default ----------------

    @Test
    void computeWeather_noStatusDate_yieldsIndeterminateAndSkipsTaskReads() {
        final Project p = project(200L, null);
        when(projectRepository.findByIdAndTenantId(200L, TENANT)).thenReturn(Optional.of(p));

        final ProjectWeather weather = service().computeWeather(TENANT, 200L);

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.INDETERMINATE);
        assertThat(weather.indeterminateReason())
                .isEqualTo(ProjectWeatherIndeterminateReason.MISSING_STATUS_DATE);
        assertThat(weather.actualProgressPercent()).isNull();
        assertThat(weather.expectedProgressPercent()).isNull();
        assertThat(weather.varianceInPoints()).isNull();
        verify(taskRepository, never()).findAllByProjectIdAndTenantId(any(), any());
    }

    @Test
    void computeWeather_noTasks_yieldsIndeterminateMissingWindow() {
        final Project p = project(201L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(201L, TENANT)).thenReturn(Optional.of(p));
        when(taskRepository.findAllByProjectIdAndTenantId(201L, TENANT)).thenReturn(List.of());

        final ProjectWeather weather = service().computeWeather(TENANT, 201L);

        assertThat(weather.status()).isEqualTo(ProjectWeatherStatus.INDETERMINATE);
        assertThat(weather.indeterminateReason()).isEqualTo(ProjectWeatherIndeterminateReason.MISSING_WINDOW);
        assertThat(weather.asOfDate()).isEqualTo(LocalDate.of(2024, 1, 6));
    }

    @Test
    void computeWeather_tasksWithoutAnyDate_yieldsIndeterminateMissingWindow() {
        final Project p = project(202L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(202L, TENANT)).thenReturn(Optional.of(p));
        final Task undated = new Task(TENANT, TEAM, 0L, 0, "undated", NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        setId(undated, 7L);
        when(taskRepository.findAllByProjectIdAndTenantId(202L, TENANT)).thenReturn(List.of(undated));

        final ProjectWeather weather = service().computeWeather(TENANT, 202L);

        assertThat(weather.indeterminateReason()).isEqualTo(ProjectWeatherIndeterminateReason.MISSING_WINDOW);
    }

    @Test
    void computeWeather_inconsistentWindow_yieldsIndeterminateMissingWindow() {
        final Project p = project(203L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(203L, TENANT)).thenReturn(Optional.of(p));
        // one task only has a (late) start, another only has an (earlier) finish -> finish < start
        final Task onlyStart = new Task(TENANT, TEAM, 0L, 0, "s", NodeKind.LEAF, false, TemporalPrecision.DAY, 0);
        setId(onlyStart, 8L);
        onlyStart.setFuzzyPeriodStart(LocalDate.of(2024, 6, 1));
        final Task onlyFinish = new Task(TENANT, TEAM, 0L, 1, "f", NodeKind.LEAF, false, TemporalPrecision.DAY, 0);
        setId(onlyFinish, 9L);
        onlyFinish.setFuzzyPeriodEnd(LocalDate.of(2024, 1, 1));
        when(taskRepository.findAllByProjectIdAndTenantId(203L, TENANT))
                .thenReturn(List.of(onlyStart, onlyFinish));

        final ProjectWeather weather = service().computeWeather(TENANT, 203L);

        assertThat(weather.indeterminateReason()).isEqualTo(ProjectWeatherIndeterminateReason.MISSING_WINDOW);
    }

    @Test
    void computeWeather_noLeafProgressRecord_yieldsIndeterminateMissingProgress() {
        final Project p = project(204L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(204L, TENANT)).thenReturn(Optional.of(p));
        final Task task = leafWithWindow(10L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(204L, TENANT)).thenReturn(List.of(task));
        when(taskProgressRepository.findByTaskIdAndTenantId(10L, TENANT)).thenReturn(Optional.empty());

        final ProjectWeather weather = service().computeWeather(TENANT, 204L);

        assertThat(weather.indeterminateReason()).isEqualTo(ProjectWeatherIndeterminateReason.MISSING_PROGRESS);
    }

    @Test
    void computeWeather_summaryTaskProgressIgnored_yieldsIndeterminateMissingProgress() {
        final Project p = project(205L, LocalDate.of(2024, 1, 6));
        when(projectRepository.findByIdAndTenantId(205L, TENANT)).thenReturn(Optional.of(p));
        final Task summary = new Task(TENANT, TEAM, 0L, 0, "summary", NodeKind.SUMMARY, false,
                TemporalPrecision.DAY, 0);
        setId(summary, 11L);
        summary.setFuzzyPeriodStart(LocalDate.of(2024, 1, 1));
        summary.setFuzzyPeriodEnd(LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(205L, TENANT)).thenReturn(List.of(summary));

        final ProjectWeather weather = service().computeWeather(TENANT, 205L);

        assertThat(weather.indeterminateReason()).isEqualTo(ProjectWeatherIndeterminateReason.MISSING_PROGRESS);
        verify(taskProgressRepository, never()).findByTaskIdAndTenantId(any(), any());
    }

    // -------- AC2: batch (application) path — single source of truth, no divergent recompute --

    @Test
    void computeWeatherForApplication_returnsEachProjectsWeatherOrderedById() {
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.of(application()));

        final Project sunny = project(300L, LocalDate.of(2024, 1, 6));
        final Project indeterminate = project(301L, null);
        when(projectRepository.findAllByApplicationIdAndTenantId(APP, TENANT))
                .thenReturn(List.of(indeterminate, sunny));
        final Task task = leafWithWindow(20L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 11));
        when(taskRepository.findAllByProjectIdAndTenantId(300L, TENANT)).thenReturn(List.of(task));
        stubProgress(20L, new BigDecimal("50.00"));

        final List<ProjectWeather> weathers = service().computeWeatherForApplication(TENANT, APP);

        assertThat(weathers).extracting(ProjectWeather::projectId).containsExactly(300L, 301L);
        assertThat(weathers.get(0).status()).isEqualTo(ProjectWeatherStatus.SUNNY);
        assertThat(weathers.get(1).status()).isEqualTo(ProjectWeatherStatus.INDETERMINATE);
    }

    @Test
    void computeWeatherForApplication_unknownOrCrossTenantApplication_throwsAndReadsNoProjects() {
        when(applicationRepository.findByIdAndTenantId(APP, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().computeWeatherForApplication(TENANT, APP))
                .isInstanceOf(ApplicationNotFoundException.class)
                .hasMessageContaining(String.valueOf(APP));

        verify(projectRepository, never()).findAllByApplicationIdAndTenantId(any(), any());
    }

    // -------- Security: unknown/cross-tenant project → ProjectNotFoundException (404-equivalent) -

    @Test
    void computeWeather_unknownOrCrossTenantProject_throws() {
        when(projectRepository.findByIdAndTenantId(999L, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().computeWeather(TENANT, 999L))
                .isInstanceOf(ProjectNotFoundException.class)
                .hasMessageContaining("999");

        verify(taskRepository, never()).findAllByProjectIdAndTenantId(any(), any());
    }
}

package fr.pivot.pilotage.baseline;

import fr.pivot.pilotage.project.Application;
import fr.pivot.pilotage.project.Project;
import fr.pivot.pilotage.schedule.Calendar;
import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.schedule.SchedulingMode;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO unit tests for the EN22.1a {@code baseline} entities (Baseline, BaselineSnapshot) and
 * for the four new temporal accessors added to {@link Project} by EN22.1a.
 *
 * <p>Exercises constructors, getters, setters and lifecycle callbacks without a Spring context or
 * a database, covering the accessor lines the Testcontainers integration tests do not reach so the
 * module stays above the jacoco {@code LINE COVEREDRATIO} threshold (0.80).
 */
class BaselinePojoTest {

    private static final Long TENANT_ID = 42L;
    private static final Long PROJECT_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-07-11T10:15:30Z");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 11);

    @Test
    void baselineConstructorGettersAndCallbacks() {
        final Baseline b = new Baseline(TENANT_ID, PROJECT_ID, (short) 0, NOW);

        assertThat(b.getId()).isNull();
        assertThat(b.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(b.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(b.getBaselineIndex()).isEqualTo((short) 0);
        assertThat(b.getCapturedAt()).isEqualTo(NOW);

        final Baseline blank = new Baseline();
        blank.prePersist();
        assertThat(blank.getCreatedAt()).isNotNull();
        assertThat(blank.getUpdatedAt()).isNotNull();

        final Instant firstCreated = blank.getCreatedAt();
        blank.prePersist();
        assertThat(blank.getCreatedAt()).isEqualTo(firstCreated);
        blank.preUpdate();
        assertThat(blank.getUpdatedAt()).isNotNull();
    }

    @Test
    void baselineSnapshotConstructorAllAccessorsAndCallbacks() {
        final BaselineSnapshot s = new BaselineSnapshot(TENANT_ID, 3L, 5L);

        assertThat(s.getId()).isNull();
        assertThat(s.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(s.getBaselineId()).isEqualTo(3L);
        assertThat(s.getTaskId()).isEqualTo(5L);
        assertThat(s.getBlStart()).isNull();
        assertThat(s.getBlFinish()).isNull();
        assertThat(s.getBlDurationMinutes()).isNull();
        assertThat(s.getBlWorkMinutes()).isNull();
        assertThat(s.getBlCostAmount()).isNull();
        assertThat(s.getBlTemporalPrecision()).isNull();

        s.setBlStart(NOW);
        s.setBlFinish(NOW.plusSeconds(3600));
        s.setBlDurationMinutes(60);
        s.setBlWorkMinutes(480);
        s.setBlCostAmount(new BigDecimal("1234.56"));
        s.setBlTemporalPrecision(TemporalPrecision.MONTH);

        assertThat(s.getBlStart()).isEqualTo(NOW);
        assertThat(s.getBlFinish()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(s.getBlDurationMinutes()).isEqualTo(60);
        assertThat(s.getBlWorkMinutes()).isEqualTo(480);
        assertThat(s.getBlCostAmount()).isEqualByComparingTo("1234.56");
        assertThat(s.getBlTemporalPrecision()).isEqualTo(TemporalPrecision.MONTH);

        s.prePersist();
        s.preUpdate();
        assertThat(s.getCreatedAt()).isNotNull();
        assertThat(s.getUpdatedAt()).isNotNull();
    }

    @Test
    void projectTemporalAccessorsRoundTrip() {
        final Application app = new Application(TENANT_ID, "App", NOW);
        final Project project = new Project(app, TENANT_ID, "Project", NOW);

        // schedulingMode defaults to AUTO; the other temporal fields default to null
        assertThat(project.getSchedulingMode()).isEqualTo(SchedulingMode.AUTO);
        assertThat(project.getCalendar()).isNull();
        assertThat(project.getStatusDate()).isNull();
        assertThat(project.getDefaultTemporalPrecision()).isNull();

        final Calendar calendar = new Calendar(TENANT_ID, PROJECT_ID, CalendarScope.PROJECT,
                "Standard", (short) 0b0011111, "{}");
        project.setCalendar(calendar);
        project.setSchedulingMode(SchedulingMode.MANUAL);
        project.setStatusDate(DAY);
        project.setDefaultTemporalPrecision(TemporalPrecision.QUARTER);

        assertThat(project.getCalendar()).isSameAs(calendar);
        assertThat(project.getSchedulingMode()).isEqualTo(SchedulingMode.MANUAL);
        assertThat(project.getStatusDate()).isEqualTo(DAY);
        assertThat(project.getDefaultTemporalPrecision()).isEqualTo(TemporalPrecision.QUARTER);
    }
}

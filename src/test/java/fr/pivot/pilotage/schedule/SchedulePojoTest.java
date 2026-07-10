package fr.pivot.pilotage.schedule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-POJO unit tests for the EN22.1a {@code schedule} entities (Calendar, CalendarException,
 * Task, Phase, TaskDependency, TaskConstraint, Assignment, TaskProgress) and their enums.
 *
 * <p>Each test exercises the constructors, getters, setters and lifecycle callbacks
 * ({@code @PrePersist}/{@code @PreUpdate}) without a Spring context or a database, covering the
 * "schema-only" accessor lines that the Testcontainers integration tests do not reach so the
 * module stays above the jacoco {@code LINE COVEREDRATIO} threshold (0.80).
 */
class SchedulePojoTest {

    private static final Long TENANT_ID = 42L;
    private static final Long PROJECT_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-07-11T10:15:30Z");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 11);

    @Test
    void calendarConstructorGettersSettersAndCallbacks() {
        final Calendar cal = new Calendar(TENANT_ID, PROJECT_ID, CalendarScope.PROJECT,
                "Standard", (short) 0b0011111, "{}");

        assertThat(cal.getId()).isNull();
        assertThat(cal.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(cal.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(cal.getScope()).isEqualTo(CalendarScope.PROJECT);
        assertThat(cal.getName()).isEqualTo("Standard");
        assertThat(cal.getWorkingDaysMask()).isEqualTo((short) 0b0011111);
        assertThat(cal.getWorkingTime()).isEqualTo("{}");

        cal.setName("Custom");
        cal.setWorkingDaysMask((short) 0b1111111);
        cal.setWorkingTime("{\"mon\":[]}");
        assertThat(cal.getName()).isEqualTo("Custom");
        assertThat(cal.getWorkingDaysMask()).isEqualTo((short) 0b1111111);
        assertThat(cal.getWorkingTime()).isEqualTo("{\"mon\":[]}");

        final Calendar blank = new Calendar();
        blank.prePersist();
        assertThat(blank.getCreatedAt()).isNotNull();
        assertThat(blank.getUpdatedAt()).isNotNull();

        // second prePersist keeps the already-set timestamps; preUpdate refreshes updatedAt
        final Instant firstCreated = blank.getCreatedAt();
        blank.prePersist();
        assertThat(blank.getCreatedAt()).isEqualTo(firstCreated);
        blank.preUpdate();
        assertThat(blank.getUpdatedAt()).isNotNull();
    }

    @Test
    void calendarScopeValuesAreInstantiable() {
        assertThat(CalendarScope.values()).containsExactly(
                CalendarScope.PROJECT, CalendarScope.TASK, CalendarScope.RESOURCE);
        assertThat(CalendarScope.valueOf("TASK")).isEqualTo(CalendarScope.TASK);
    }

    @Test
    void calendarExceptionConstructorGettersAndCallbacks() {
        final CalendarException ex = new CalendarException(TENANT_ID, 3L, DAY, true, "{}");

        assertThat(ex.getId()).isNull();
        assertThat(ex.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(ex.getCalendarId()).isEqualTo(3L);
        assertThat(ex.getExceptionDate()).isEqualTo(DAY);
        assertThat(ex.getWorking()).isTrue();
        assertThat(ex.getWorkingTime()).isEqualTo("{}");

        // CalendarException exposes no timestamp getters; exercise the lifecycle callbacks only
        final CalendarException blank = new CalendarException();
        blank.prePersist();
        blank.prePersist();
        blank.preUpdate();
        assertThat(blank.getWorking()).isNull();
    }

    @Test
    void taskConstructorAllAccessorsAndCallbacks() {
        final Task task = new Task(TENANT_ID, PROJECT_ID, 1, "Design",
                NodeKind.LEAF, Boolean.TRUE, TemporalPrecision.DAY, 0);

        assertThat(task.getId()).isNull();
        assertThat(task.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(task.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(task.getPosition()).isEqualTo(1);
        assertThat(task.getName()).isEqualTo("Design");
        assertThat(task.getNodeKind()).isEqualTo(NodeKind.LEAF);
        assertThat(task.getSharedInRoadmap()).isTrue();
        assertThat(task.getTemporalPrecision()).isEqualTo(TemporalPrecision.DAY);
        assertThat(task.getRevision()).isZero();

        // derived / nullable fields default to null
        assertThat(task.getPhaseId()).isNull();
        assertThat(task.getParentTaskId()).isNull();
        assertThat(task.getWbsCode()).isNull();
        assertThat(task.getStartDate()).isNull();
        assertThat(task.getFinishDate()).isNull();
        assertThat(task.getDurationMinutes()).isNull();
        assertThat(task.getEarlyStart()).isNull();
        assertThat(task.getEarlyFinish()).isNull();
        assertThat(task.getLateStart()).isNull();
        assertThat(task.getLateFinish()).isNull();
        assertThat(task.getTotalSlackMinutes()).isNull();
        assertThat(task.getFreeSlackMinutes()).isNull();
        assertThat(task.getCritical()).isNull();
        assertThat(task.getSchedulingMode()).isNull();
        assertThat(task.getCalendarId()).isNull();
        assertThat(task.getRecurrenceRule()).isNull();
        assertThat(task.getFuzzyPeriodStart()).isNull();
        assertThat(task.getFuzzyPeriodEnd()).isNull();

        task.setPhaseId(11L);
        task.setParentTaskId(12L);
        task.setPosition(2);
        task.setName("Build");
        task.setNodeKind(NodeKind.SUMMARY);
        task.setSharedInRoadmap(Boolean.FALSE);
        task.setTemporalPrecision(TemporalPrecision.WEEK);
        task.setFuzzyPeriodStart(DAY);
        task.setFuzzyPeriodEnd(DAY.plusDays(5));
        task.setStartDate(NOW);
        task.setFinishDate(NOW.plusSeconds(3600));
        task.setDurationMinutes(60);
        task.setSchedulingMode(SchedulingMode.MANUAL);
        task.setCalendarId(99L);
        task.setRecurrenceRule("FREQ=WEEKLY");
        task.setRevision(1);

        assertThat(task.getPhaseId()).isEqualTo(11L);
        assertThat(task.getParentTaskId()).isEqualTo(12L);
        assertThat(task.getPosition()).isEqualTo(2);
        assertThat(task.getName()).isEqualTo("Build");
        assertThat(task.getNodeKind()).isEqualTo(NodeKind.SUMMARY);
        assertThat(task.getSharedInRoadmap()).isFalse();
        assertThat(task.getTemporalPrecision()).isEqualTo(TemporalPrecision.WEEK);
        assertThat(task.getFuzzyPeriodStart()).isEqualTo(DAY);
        assertThat(task.getFuzzyPeriodEnd()).isEqualTo(DAY.plusDays(5));
        assertThat(task.getStartDate()).isEqualTo(NOW);
        assertThat(task.getFinishDate()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(task.getDurationMinutes()).isEqualTo(60);
        assertThat(task.getSchedulingMode()).isEqualTo(SchedulingMode.MANUAL);
        assertThat(task.getCalendarId()).isEqualTo(99L);
        assertThat(task.getRecurrenceRule()).isEqualTo("FREQ=WEEKLY");
        assertThat(task.getRevision()).isEqualTo(1);

        task.prePersist();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
        task.preUpdate();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void scheduleEnumsAreInstantiable() {
        assertThat(NodeKind.values()).containsExactly(
                NodeKind.SUMMARY, NodeKind.LEAF, NodeKind.MILESTONE, NodeKind.RECURRING);
        assertThat(SchedulingMode.values()).containsExactly(SchedulingMode.AUTO, SchedulingMode.MANUAL);
        assertThat(TemporalPrecision.values()).containsExactly(
                TemporalPrecision.SEMESTER, TemporalPrecision.QUARTER, TemporalPrecision.MONTH,
                TemporalPrecision.WEEK, TemporalPrecision.DAY);
        assertThat(DependencyLinkType.values()).containsExactly(
                DependencyLinkType.FS, DependencyLinkType.SS, DependencyLinkType.FF, DependencyLinkType.SF);
        assertThat(ConstraintType.values()).containsExactly(
                ConstraintType.ASAP, ConstraintType.ALAP, ConstraintType.MSO, ConstraintType.MFO,
                ConstraintType.SNET, ConstraintType.SNLT, ConstraintType.FNET, ConstraintType.FNLT);
    }

    @Test
    void phaseConstructorGettersSettersAndCallbacks() {
        final Phase phase = new Phase(TENANT_ID, PROJECT_ID, 5L, "Kickoff", 0);

        assertThat(phase.getId()).isNull();
        assertThat(phase.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(phase.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(phase.getParentTaskId()).isEqualTo(5L);
        assertThat(phase.getName()).isEqualTo("Kickoff");
        assertThat(phase.getPosition()).isZero();

        phase.setParentTaskId(6L);
        phase.setName("Delivery");
        phase.setPosition(1);
        assertThat(phase.getParentTaskId()).isEqualTo(6L);
        assertThat(phase.getName()).isEqualTo("Delivery");
        assertThat(phase.getPosition()).isEqualTo(1);

        phase.prePersist();
        phase.preUpdate();
        assertThat(phase.getCreatedAt()).isNotNull();
        assertThat(phase.getUpdatedAt()).isNotNull();
    }

    @Test
    void taskDependencyConstructorGettersSettersAndCallbacks() {
        final TaskDependency dep = new TaskDependency(TENANT_ID, 1L, 2L, DependencyLinkType.FS, 0);

        assertThat(dep.getId()).isNull();
        assertThat(dep.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(dep.getPredecessorTaskId()).isEqualTo(1L);
        assertThat(dep.getSuccessorTaskId()).isEqualTo(2L);
        assertThat(dep.getLinkType()).isEqualTo(DependencyLinkType.FS);
        assertThat(dep.getLagMinutes()).isZero();

        dep.setLinkType(DependencyLinkType.SS);
        dep.setLagMinutes(120);
        assertThat(dep.getLinkType()).isEqualTo(DependencyLinkType.SS);
        assertThat(dep.getLagMinutes()).isEqualTo(120);

        dep.prePersist();
        dep.preUpdate();
        assertThat(dep.getCreatedAt()).isNotNull();
        assertThat(dep.getUpdatedAt()).isNotNull();
    }

    @Test
    void taskConstraintConstructorGettersSettersAndCallbacks() {
        final TaskConstraint c = new TaskConstraint(TENANT_ID, 1L, ConstraintType.SNET, NOW, NOW.plusSeconds(60));

        assertThat(c.getId()).isNull();
        assertThat(c.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(c.getTaskId()).isEqualTo(1L);
        assertThat(c.getConstraintType()).isEqualTo(ConstraintType.SNET);
        assertThat(c.getConstraintDate()).isEqualTo(NOW);
        assertThat(c.getDeadline()).isEqualTo(NOW.plusSeconds(60));

        c.setConstraintType(ConstraintType.MFO);
        c.setConstraintDate(NOW.plusSeconds(10));
        c.setDeadline(NOW.plusSeconds(120));
        assertThat(c.getConstraintType()).isEqualTo(ConstraintType.MFO);
        assertThat(c.getConstraintDate()).isEqualTo(NOW.plusSeconds(10));
        assertThat(c.getDeadline()).isEqualTo(NOW.plusSeconds(120));

        c.prePersist();
        c.preUpdate();
        assertThat(c.getCreatedAt()).isNotNull();
        assertThat(c.getUpdatedAt()).isNotNull();
    }

    @Test
    void assignmentConstructorAllAccessorsAndCallbacks() {
        final Assignment a = new Assignment(TENANT_ID, 1L, "user:99", new BigDecimal("100.00"));

        assertThat(a.getId()).isNull();
        assertThat(a.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(a.getTaskId()).isEqualTo(1L);
        assertThat(a.getResourceRef()).isEqualTo("user:99");
        assertThat(a.getUnitsPercent()).isEqualByComparingTo("100.00");
        assertThat(a.getWorkMinutes()).isNull();
        assertThat(a.getActualWorkMinutes()).isNull();
        assertThat(a.getRemainingWorkMinutes()).isNull();
        assertThat(a.getCostAmount()).isNull();
        assertThat(a.getCostCurrency()).isNull();
        assertThat(a.getActualCostAmount()).isNull();

        a.setResourceRef("team:1");
        a.setUnitsPercent(new BigDecimal("50.00"));
        a.setWorkMinutes(480);
        a.setActualWorkMinutes(240);
        a.setRemainingWorkMinutes(240);
        a.setCostAmount(new BigDecimal("1000.00"));
        a.setCostCurrency("EUR");
        a.setActualCostAmount(new BigDecimal("500.00"));

        assertThat(a.getResourceRef()).isEqualTo("team:1");
        assertThat(a.getUnitsPercent()).isEqualByComparingTo("50.00");
        assertThat(a.getWorkMinutes()).isEqualTo(480);
        assertThat(a.getActualWorkMinutes()).isEqualTo(240);
        assertThat(a.getRemainingWorkMinutes()).isEqualTo(240);
        assertThat(a.getCostAmount()).isEqualByComparingTo("1000.00");
        assertThat(a.getCostCurrency()).isEqualTo("EUR");
        assertThat(a.getActualCostAmount()).isEqualByComparingTo("500.00");

        a.prePersist();
        a.preUpdate();
        assertThat(a.getCreatedAt()).isNotNull();
        assertThat(a.getUpdatedAt()).isNotNull();
    }

    @Test
    void taskProgressConstructorAllAccessorsAndCallbacks() {
        final TaskProgress p = new TaskProgress(TENANT_ID, 1L, new BigDecimal("25.00"));

        assertThat(p.getId()).isNull();
        assertThat(p.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(p.getTaskId()).isEqualTo(1L);
        assertThat(p.getPercentComplete()).isEqualByComparingTo("25.00");
        assertThat(p.getPhysicalPercentComplete()).isNull();
        assertThat(p.getActualStart()).isNull();
        assertThat(p.getActualFinish()).isNull();
        assertThat(p.getStatusDate()).isNull();

        p.setPercentComplete(new BigDecimal("75.00"));
        p.setPhysicalPercentComplete(new BigDecimal("70.00"));
        p.setActualStart(NOW);
        p.setActualFinish(NOW.plusSeconds(3600));
        p.setStatusDate(DAY);

        assertThat(p.getPercentComplete()).isEqualByComparingTo("75.00");
        assertThat(p.getPhysicalPercentComplete()).isEqualByComparingTo("70.00");
        assertThat(p.getActualStart()).isEqualTo(NOW);
        assertThat(p.getActualFinish()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(p.getStatusDate()).isEqualTo(DAY);

        p.prePersist();
        p.preUpdate();
        assertThat(p.getCreatedAt()).isNotNull();
        assertThat(p.getUpdatedAt()).isNotNull();
    }
}

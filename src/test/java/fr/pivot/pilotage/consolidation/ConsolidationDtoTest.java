package fr.pivot.pilotage.consolidation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the immutable consolidation DTO records (EN18.9): defensive copies on construction
 * and accessors (SpotBugs {@code EI_EXPOSE_REP}) and the contribution validation rules.
 */
class ConsolidationDtoTest {

    @Test
    void applicationConsolidation_defensivelyCopiesCollections() {
        final Map<ProjectPlanningStatus, Integer> status = new HashMap<>();
        status.put(ProjectPlanningStatus.EMPTY, 1);
        final List<ApplicationMilestone> milestones = new java.util.ArrayList<>(
                List.of(new ApplicationMilestone(1L, 10L, "M", null, null)));
        final List<ApplicationAggregateContribution> contributions = new java.util.ArrayList<>();

        final ApplicationConsolidation c = new ApplicationConsolidation(42L, "App", 7L, 1, status,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31), milestones, contributions);

        // mutating the sources must not affect the record
        status.put(ProjectPlanningStatus.SCHEDULED, 99);
        milestones.clear();
        contributions.add(new ApplicationAggregateContribution("x", Map.of()));

        assertThat(c.projectsByStatus()).containsExactly(Map.entry(ProjectPlanningStatus.EMPTY, 1));
        assertThat(c.strategicMilestones()).hasSize(1);
        assertThat(c.contributions()).isEmpty();
        // accessors are unmodifiable
        assertThatThrownBy(() -> c.strategicMilestones().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.projectsByStatus().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contribution_defensivelyCopiesMetrics_andRejectsBlankModuleId() {
        final Map<String, Object> metrics = new HashMap<>();
        metrics.put("k", 1);
        final ApplicationAggregateContribution c =
                new ApplicationAggregateContribution("budget", metrics);
        metrics.put("k", 2);

        assertThat(c.metrics()).containsExactly(Map.entry("k", 1));
        assertThatThrownBy(() -> c.metrics().put("z", 3))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new ApplicationAggregateContribution("  ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

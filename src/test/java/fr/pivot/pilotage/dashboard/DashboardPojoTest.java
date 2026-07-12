package fr.pivot.pilotage.dashboard;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-POJO unit tests for the US23.2.2 {@link DashboardConfig}/{@link DashboardWidget} entities —
 * mirrors {@code fr.pivot.pilotage.roadmap.LanePojoTest}: exercises constructors, getters,
 * lifecycle callbacks and the {@code replaceWidgets} full-replace semantics without a Spring
 * context or a database.
 */
class DashboardPojoTest {

    private static final Long TENANT_ID = 42L;
    private static final Long TEAM_ID = 5L;
    private static final Long USER_ID = 7L;
    private static final Long APPLICATION_ID = 100L;

    @Test
    void configFullConstructorInitializesAllFields() {
        final DashboardConfig config =
                new DashboardConfig(TENANT_ID, TEAM_ID, USER_ID, "PMO", DashboardViewMode.SYNTHETIC);

        assertThat(config.getId()).isNull();
        assertThat(config.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(config.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(config.getUserId()).isEqualTo(USER_ID);
        assertThat(config.getProfile()).isEqualTo("PMO");
        assertThat(config.getViewMode()).isEqualTo(DashboardViewMode.SYNTHETIC);
        assertThat(config.getWidgets()).isEmpty();
    }

    @Test
    void configPrePersistSetsBothTimestampsWhenUnset() {
        final DashboardConfig config =
                new DashboardConfig(TENANT_ID, TEAM_ID, USER_ID, "PMO", DashboardViewMode.SYNTHETIC);

        config.prePersist();

        assertThat(config.getCreatedAt()).isNotNull();
        assertThat(config.getUpdatedAt()).isNotNull();
    }

    @Test
    void configPrePersistPreservesAlreadySetTimestampsAndPreUpdateRefreshesUpdatedAt() {
        final DashboardConfig config =
                new DashboardConfig(TENANT_ID, TEAM_ID, USER_ID, "PMO", DashboardViewMode.SYNTHETIC);
        config.prePersist();
        final Instant firstCreated = config.getCreatedAt();
        final Instant firstUpdated = config.getUpdatedAt();

        config.prePersist();
        assertThat(config.getCreatedAt()).isEqualTo(firstCreated);
        assertThat(config.getUpdatedAt()).isEqualTo(firstUpdated);

        config.preUpdate();
        assertThat(config.getUpdatedAt()).isNotNull();
        assertThat(config.getCreatedAt()).isEqualTo(firstCreated);
    }

    @Test
    void configNoArgConstructorAllowsLifecycleCallbacksBeforeFieldsAreSet() {
        final DashboardConfig blank = newBlankConfig();

        blank.prePersist();

        assertThat(blank.getId()).isNull();
        assertThat(blank.getCreatedAt()).isNotNull();
        assertThat(blank.getUpdatedAt()).isNotNull();
    }

    @Test
    void replaceWidgets_replacesProfileViewModeAndReparentsEachWidget() {
        final DashboardConfig config =
                new DashboardConfig(TENANT_ID, TEAM_ID, USER_ID, "PMO", DashboardViewMode.SYNTHETIC);
        final DashboardWidget first = new DashboardWidget(TENANT_ID, TEAM_ID, APPLICATION_ID,
                DashboardWidgetType.STRATEGIC_MILESTONES, 0, 0, 0, 1, 1);

        config.replaceWidgets("Sponsor", DashboardViewMode.DETAILED, List.of(first));

        assertThat(config.getProfile()).isEqualTo("Sponsor");
        assertThat(config.getViewMode()).isEqualTo(DashboardViewMode.DETAILED);
        assertThat(config.getWidgets()).containsExactly(first);
        assertThat(first.getDashboardConfig()).isSameAs(config);
    }

    @Test
    void replaceWidgets_calledTwice_dropsThePreviousWidgetsEntirely() {
        final DashboardConfig config =
                new DashboardConfig(TENANT_ID, TEAM_ID, USER_ID, "PMO", DashboardViewMode.SYNTHETIC);
        final DashboardWidget old = new DashboardWidget(TENANT_ID, TEAM_ID, APPLICATION_ID,
                DashboardWidgetType.STRATEGIC_MILESTONES, 0, 0, 0, 1, 1);
        config.replaceWidgets("PMO", DashboardViewMode.SYNTHETIC, List.of(old));

        final DashboardWidget replacement = new DashboardWidget(TENANT_ID, TEAM_ID, APPLICATION_ID,
                DashboardWidgetType.WEATHER_ALERTS, 0, 0, 0, 1, 1);
        config.replaceWidgets("PMO", DashboardViewMode.SYNTHETIC, List.of(replacement));

        assertThat(config.getWidgets()).containsExactly(replacement);
    }

    @Test
    void getWidgets_isUnmodifiable() {
        final DashboardConfig config =
                new DashboardConfig(TENANT_ID, TEAM_ID, USER_ID, "PMO", DashboardViewMode.SYNTHETIC);

        assertThatThrownBy(() -> config.getWidgets().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void widgetFullConstructorInitializesAllFields() {
        final DashboardWidget widget = new DashboardWidget(TENANT_ID, TEAM_ID, APPLICATION_ID,
                DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY, 2, 1, 0, 2, 1);

        assertThat(widget.getId()).isNull();
        assertThat(widget.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(widget.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(widget.getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(widget.getWidgetType()).isEqualTo(DashboardWidgetType.PORTFOLIO_STATUS_SUMMARY);
        assertThat(widget.getPosition()).isEqualTo(2);
        assertThat(widget.getGridRow()).isEqualTo(1);
        assertThat(widget.getGridColumn()).isZero();
        assertThat(widget.getGridWidth()).isEqualTo(2);
        assertThat(widget.getGridHeight()).isEqualTo(1);
    }

    @Test
    void widgetPrePersistAndPreUpdateManageTimestamps() {
        final DashboardWidget widget = new DashboardWidget(TENANT_ID, TEAM_ID, APPLICATION_ID,
                DashboardWidgetType.WEATHER_ALERTS, 0, 0, 0, 1, 1);

        widget.prePersist();
        assertThat(widget.getCreatedAt()).isNotNull();
        assertThat(widget.getUpdatedAt()).isNotNull();

        widget.preUpdate();
        assertThat(widget.getUpdatedAt()).isNotNull();
    }

    @Test
    void widgetSetDashboardConfigUpdatesTheParentReference() {
        final DashboardWidget widget = new DashboardWidget(TENANT_ID, TEAM_ID, APPLICATION_ID,
                DashboardWidgetType.WEATHER_ALERTS, 0, 0, 0, 1, 1);
        final DashboardConfig config =
                new DashboardConfig(TENANT_ID, TEAM_ID, USER_ID, "PMO", DashboardViewMode.SYNTHETIC);

        widget.setDashboardConfig(config);

        assertThat(widget.getDashboardConfig()).isSameAs(config);
    }

    @Test
    void widgetNoArgConstructorAllowsLifecycleCallbacksBeforeFieldsAreSet() {
        final DashboardWidget blank = newBlankWidget();

        blank.prePersist();

        assertThat(blank.getId()).isNull();
        assertThat(blank.getCreatedAt()).isNotNull();
        assertThat(blank.getUpdatedAt()).isNotNull();
    }

    private static DashboardConfig newBlankConfig() {
        try {
            final var ctor = DashboardConfig.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("DashboardConfig must expose a no-arg constructor for JPA", e);
        }
    }

    private static DashboardWidget newBlankWidget() {
        try {
            final var ctor = DashboardWidget.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("DashboardWidget must expose a no-arg constructor for JPA", e);
        }
    }
}

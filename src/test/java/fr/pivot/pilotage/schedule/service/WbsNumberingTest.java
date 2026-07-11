package fr.pivot.pilotage.schedule.service;

import fr.pivot.pilotage.schedule.NodeKind;
import fr.pivot.pilotage.schedule.Task;
import fr.pivot.pilotage.schedule.TemporalPrecision;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WbsNumbering} — deterministic server-side WBS code derivation (EN22.1b).
 */
class WbsNumberingTest {

    private static Task task(final long id, final Long parentId, final int position) {
        final Task t = new Task(7L, 100L, position, "T" + id, NodeKind.LEAF, false,
                TemporalPrecision.DAY, 0);
        setId(t, id);
        t.setParentTaskId(parentId);
        return t;
    }

    private static void setId(final Task t, final long id) {
        try {
            final Field f = Task.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void derivesHierarchicalCodesInPositionOrder() {
        // Root 1 (task 10) with children 11, 12; root 2 (task 20) with child 21.
        final List<Task> tasks = List.of(
                task(10, null, 0), task(11, 10L, 0), task(12, 10L, 1),
                task(20, null, 1), task(21, 20L, 0));
        final Map<Long, String> codes = WbsNumbering.derive(tasks);

        assertThat(codes.get(10L)).isEqualTo("1");
        assertThat(codes.get(11L)).isEqualTo("1.1");
        assertThat(codes.get(12L)).isEqualTo("1.2");
        assertThat(codes.get(20L)).isEqualTo("2");
        assertThat(codes.get(21L)).isEqualTo("2.1");
    }

    @Test
    void isStableWhenPositionsTie() {
        // Equal positions ⇒ tie-broken by id, deterministically.
        final List<Task> tasks = List.of(task(30, null, 0), task(31, null, 0));
        final Map<Long, String> codes = WbsNumbering.derive(tasks);
        assertThat(codes.get(30L)).isEqualTo("1");
        assertThat(codes.get(31L)).isEqualTo("2");
    }
}

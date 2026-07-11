package fr.pivot.pilotage.schedule.service;

import fr.pivot.pilotage.schedule.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives WBS codes (EN22.1b) — the server-side {@code wbs_code} (parent + rank) materialised on
 * each task. Roots are numbered {@code 1, 2, ...} in {@code position} order; children extend the
 * parent's code with a dot and their own rank ({@code 1.1}, {@code 1.2}, {@code 1.2.1}). Pure and
 * deterministic: ordering is by {@code (position, id)} so equal positions still yield a stable code.
 */
final class WbsNumbering {

    private WbsNumbering() {
    }

    /**
     * Computes the WBS code for every task, keyed by task id.
     *
     * @param tasks the project's tasks
     * @return a map from task id to derived WBS code
     */
    static Map<Long, String> derive(final List<Task> tasks) {
        final Map<Long, List<Task>> childrenByParent = new HashMap<>();
        for (final Task t : tasks) {
            childrenByParent.computeIfAbsent(t.getParentTaskId(), k -> new ArrayList<>()).add(t);
        }
        for (final List<Task> siblings : childrenByParent.values()) {
            siblings.sort(Comparator.comparingInt(Task::getPosition).thenComparing(Task::getId));
        }
        final Map<Long, String> codes = new LinkedHashMap<>();
        assign(null, "", childrenByParent, codes);
        return codes;
    }

    private static void assign(final Long parentId, final String prefix,
            final Map<Long, List<Task>> childrenByParent, final Map<Long, String> codes) {
        final List<Task> siblings = childrenByParent.get(parentId);
        if (siblings == null) {
            return;
        }
        int rank = 1;
        for (final Task t : siblings) {
            final String code = prefix.isEmpty() ? String.valueOf(rank) : prefix + "." + rank;
            codes.put(t.getId(), code);
            assign(t.getId(), code, childrenByParent, codes);
            rank++;
        }
    }
}

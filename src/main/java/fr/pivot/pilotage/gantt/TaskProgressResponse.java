package fr.pivot.pilotage.gantt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO of {@code PATCH .../gantt/tasks/{taskId}/progress} (US22.4.8) — never the JPA
 * entity directly (CLAUDE.md §Standards). Reflects a task's post-save progress state: the bar
 * (percent complete) and the actual/remaining work MS-Project relation
 * (<em>remaining = work − actual</em>, implementation note), so a client can refresh both without
 * a second round trip (AC "la barre d'avancement et le travail restant se mettent à jour").
 *
 * @param taskId                  the task id
 * @param percentComplete         the saved temporal percent complete
 * @param progressLabel           textual progress rendering (e.g. {@code "45%"}), never
 *                                colour-only (A11y)
 * @param physicalPercentComplete the saved physical percent complete, or {@code null}
 * @param actualWorkMinutes       total actual work across the task's assignments
 *                                (Σ, {@code round(percentComplete% × work)}), or {@code null} when
 *                                the task carries no assignment
 * @param remainingWorkMinutes    total remaining work (Σ {@code work − actual}, floored at
 *                                {@code 0}), or {@code null} when the task carries no assignment
 * @param totalWorkMinutes        total planned work (Σ), or {@code null} when the task carries no
 *                                assignment
 * @param actualStart             the saved actual start, or {@code null}
 * @param actualFinish            the saved actual finish, or {@code null}
 * @param statusDate              the saved status (freshness) date of this entry, or {@code null}
 * @param revision                monotonic task revision — optimistic co-editing lock and event
 *                                ordering
 */
public record TaskProgressResponse(
        long taskId,
        BigDecimal percentComplete,
        String progressLabel,
        BigDecimal physicalPercentComplete,
        Integer actualWorkMinutes,
        Integer remainingWorkMinutes,
        Integer totalWorkMinutes,
        Instant actualStart,
        Instant actualFinish,
        LocalDate statusDate,
        int revision) {
}

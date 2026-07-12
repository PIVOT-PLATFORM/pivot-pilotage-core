package fr.pivot.pilotage.gantt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Request body of {@code PATCH .../gantt/tasks/{taskId}/progress} (US22.4.8) — records a task's
 * temporal completion and, optionally, its distinct physical completion, actual start/finish
 * dates and this entry's own status (freshness) date.
 *
 * <p>{@code percentComplete} is required and must lie within {@code [0, 100]}; an out-of-range
 * value is refused {@code 422} ({@link InvalidTaskProgressException#percentOutOfRange(BigDecimal)}).
 * When both {@code actualStart} and {@code actualFinish} are supplied, the finish must not precede
 * the start ({@link InvalidTaskProgressException#actualFinishBeforeActualStart}). The service
 * re-derives every assignment's actual/remaining work from {@code percentComplete}
 * (implementation note: {@code remaining = work − actual}, MS-Project parity) — a client-supplied
 * {@code actualWorkMinutes}/{@code remainingWorkMinutes} is refused {@code 422} (derived, see
 * {@link WbsExceptionHandler}).
 *
 * @param percentComplete         temporal percent complete; required, must be within {@code [0, 100]}
 * @param physicalPercentComplete distinct physical percent complete within {@code [0, 100]}, or
 *                                {@code null}
 * @param actualStart             actual start date, or {@code null}
 * @param actualFinish            actual finish date, or {@code null}; must not precede
 *                                {@code actualStart} when both are supplied
 * @param statusDate              freshness (status) date of this progress entry — distinct from the
 *                                project's own status date (used for the progress line, US22.4.8
 *                                note) — or {@code null}
 * @param actorRef                logical reference to the caller entering the value (gap-era,
 *                                ADR-006 — no cross-module FK; moves to the authenticated
 *                                principal once {@code pivot-core-starter}'s {@code TenantContext}
 *                                is consumable); required, non-blank; stamps the audit trail's
 *                                "auteur" column
 */
public record UpdateTaskProgressRequest(
        @NotNull BigDecimal percentComplete,
        BigDecimal physicalPercentComplete,
        Instant actualStart,
        Instant actualFinish,
        LocalDate statusDate,
        @NotBlank String actorRef) {
}

package fr.pivot.pilotage.schedule.engine;

import java.util.Objects;

/**
 * Unchecked exception carrying a typed {@link ScheduleErrorCode} raised by the engine (EN22.1b).
 *
 * <p>Every engine rejection path (cycle, tenant violation, unknown calendar, stale base version)
 * throws this exception with the corresponding code and no partial state is ever produced.
 */
public class ScheduleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ScheduleErrorCode code;

    /**
     * Builds a scheduling exception.
     *
     * @param code    the typed error code (never null)
     * @param message a human-readable diagnostic
     */
    public ScheduleException(final ScheduleErrorCode code, final String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * Returns the typed error code.
     *
     * @return the {@link ScheduleErrorCode}
     */
    public ScheduleErrorCode code() {
        return code;
    }
}

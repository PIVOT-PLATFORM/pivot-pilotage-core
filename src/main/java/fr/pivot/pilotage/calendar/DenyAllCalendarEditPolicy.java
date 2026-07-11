package fr.pivot.pilotage.calendar;

import org.springframework.stereotype.Component;

/**
 * Fail-closed default {@link CalendarEditPolicy} (US22.4.5) — always denies, wired as the only bean
 * until {@code pivot-core-starter} publishes project membership/roles (CLAUDE.md §gap,
 * {@code TODO-SETUP.md} §5). Mirrors {@code fr.pivot.pilotage.gantt.DenyAllWbsEditPolicy} exactly.
 *
 * <p>This is <strong>not</strong> a hardcoded role and <strong>not</strong> a silent bypass: it is
 * the explicit, documented absence of an authorization mechanism, made safe by refusing every write
 * rather than guessing or permitting everyone. Replace this bean with a real membership-checking
 * implementation once the starter is consumable; {@link CalendarController} requires no change.
 */
@Component
public class DenyAllCalendarEditPolicy implements CalendarEditPolicy {

    /**
     * {@inheritDoc}
     *
     * @return always {@code false} — no membership mechanism is wired yet
     */
    @Override
    public boolean isAuthorized() {
        return false;
    }
}

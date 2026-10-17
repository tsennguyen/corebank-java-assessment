package com.example.corebanking.fraud.domain;

/**
 * Finite state machine governing fraud alert lifecycle transitions (L3-04, FR-23, Architecture
 * §3.6).
 *
 * <p>Valid transitions:
 *
 * <ul>
 *   <li>{@code NEW -> INVESTIGATING}
 *   <li>{@code NEW -> FALSE_POSITIVE}
 *   <li>{@code INVESTIGATING -> RESOLVED}
 *   <li>{@code INVESTIGATING -> FALSE_POSITIVE}
 * </ul>
 *
 * <p>{@code RESOLVED} and {@code FALSE_POSITIVE} are terminal states. Direct transition {@code NEW
 * -> RESOLVED} is prohibited to enforce mandatory investigation before resolution.
 */
public enum AlertStatus {
    NEW,
    INVESTIGATING,
    RESOLVED,
    FALSE_POSITIVE;

    /**
     * Checks whether transitioning from the current status to {@code target} is allowed.
     *
     * @param target desired target status
     * @return {@code true} if the transition is permitted
     */
    public boolean canTransitionTo(AlertStatus target) {
        if (target == null || this == target) {
            return false;
        }
        return switch (this) {
            case NEW -> target == INVESTIGATING || target == FALSE_POSITIVE;
            case INVESTIGATING -> target == RESOLVED || target == FALSE_POSITIVE;
            case RESOLVED, FALSE_POSITIVE -> false;
        };
    }
}

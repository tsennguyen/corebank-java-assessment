package com.example.corebanking.recurring.domain;

/**
 * Lifecycle state machine for a scheduled recurring transaction instruction (L2-06, L2-07, FR-16).
 *
 * <ul>
 *   <li>{@code ACTIVE -> PAUSED | CANCELLED | COMPLETED}
 *   <li>{@code PAUSED -> ACTIVE | CANCELLED}
 *   <li>{@code CANCELLED} and {@code COMPLETED} are terminal states.
 * </ul>
 */
public enum RecurringStatus {
    ACTIVE,
    PAUSED,
    CANCELLED,
    COMPLETED;

    /**
     * Returns {@code true} if transitioning from this status to {@code target} is permitted.
     *
     * @param target desired target status
     * @return {@code true} if valid transition
     */
    public boolean canTransitionTo(RecurringStatus target) {
        if (target == null || this == target) {
            return false;
        }
        return switch (this) {
            case ACTIVE -> target == PAUSED || target == CANCELLED || target == COMPLETED;
            case PAUSED -> target == ACTIVE || target == CANCELLED;
            case CANCELLED, COMPLETED -> false;
        };
    }
}

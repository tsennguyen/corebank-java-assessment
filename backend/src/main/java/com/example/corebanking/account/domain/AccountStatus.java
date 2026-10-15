package com.example.corebanking.account.domain;

/**
 * Status lifecycle states for bank accounts enforcing finite state machine transitions: {@code
 * ACTIVE <-> LOCKED} and {@code ACTIVE | LOCKED -> CLOSED} (L2-05, FR-3).
 */
public enum AccountStatus {
    ACTIVE,
    LOCKED,
    CLOSED;

    /**
     * Evaluates whether a status transition from {@code this} to {@code target} is valid. {@code
     * CLOSED} is a terminal state and self-transitions ({@code X -> X}) are forbidden.
     */
    public boolean canTransitionTo(AccountStatus target) {
        if (target == null || this == target) {
            return false;
        }
        return switch (this) {
            case ACTIVE -> target == LOCKED || target == CLOSED;
            case LOCKED -> target == ACTIVE || target == CLOSED;
            case CLOSED -> false;
        };
    }
}

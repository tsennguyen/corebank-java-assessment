package com.example.corebanking.common.domain;

/** Exception thrown when an invalid lifecycle state transition is attempted on an entity. */
public class InvalidStatusTransitionException extends DomainException {

    public InvalidStatusTransitionException(String message) {
        super(ErrorCode.INVALID_STATUS_TRANSITION, message);
    }
}

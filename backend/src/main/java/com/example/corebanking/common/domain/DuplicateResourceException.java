package com.example.corebanking.common.domain;

/** Exception thrown when attempting to create or persist a duplicate resource. */
public class DuplicateResourceException extends DomainException {

    public DuplicateResourceException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, message);
    }
}

package com.example.corebanking.common.domain;

/** Exception thrown when a requested domain resource cannot be located. */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resourceName, Object identifier) {
        super(
                ErrorCode.RESOURCE_NOT_FOUND,
                resourceName + " not found with identifier: " + identifier);
    }
}

package com.example.corebanking.common.domain;

/**
 * Exception thrown when an operation violates a banking business rule or invariant (e.g. transfer
 * limit exceeded, insufficient balance, inactive account).
 */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessRuleException(String code, String message) {
        super(resolveErrorCode(code), message);
    }

    private static ErrorCode resolveErrorCode(String code) {
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ErrorCode.LIMIT_EXCEEDED;
        }
    }
}

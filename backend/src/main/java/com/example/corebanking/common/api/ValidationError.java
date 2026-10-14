package com.example.corebanking.common.api;

/**
 * Representation of a single field-level validation failure.
 *
 * @param field name of the invalid property or parameter
 * @param message localized constraint violation message
 */
public record ValidationError(String field, String message) {}

package com.chalkline.service;

/**
 * A problem the user can fix and should be told about in plain language --
 * a duplicate room name, a clashing booking, a course still in use.
 * Controllers turn these into a red banner rather than a stack trace.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}

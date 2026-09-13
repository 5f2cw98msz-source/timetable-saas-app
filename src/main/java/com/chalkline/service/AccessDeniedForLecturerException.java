package com.chalkline.service;

/** A lecturer tried to read or change a timetable that is not theirs. */
public class AccessDeniedForLecturerException extends RuntimeException {

    public AccessDeniedForLecturerException(String message) {
        super(message);
    }
}

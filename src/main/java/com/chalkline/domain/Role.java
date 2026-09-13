package com.chalkline.domain;

/**
 * The only two account types allowed to use this application.
 * There is deliberately no "student" or "guest" role -- the app requires a
 * login and every account is either an admin or a lecturer.
 */
public enum Role {

    ADMIN("Admin"),
    LECTURER("Lecturer");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Spring Security expects authorities to be prefixed with "ROLE_". */
    public String authority() {
        return "ROLE_" + name();
    }
}

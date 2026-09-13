package com.chalkline.domain;

/**
 * The capabilities a plan can unlock.
 *
 * Gating is checked in one place ({@code PlanPolicy}), never by hiding a
 * button. A hidden button stops nobody who can type a URL.
 */
public enum Feature {

    CALENDAR_FEED("Calendar subscriptions",
            "Lecturers subscribe to their timetable in Outlook, Google Calendar or Apple Calendar."),

    PUBLIC_SHARING("Public timetable pages",
            "Share a read-only link with students. No account needed to view it."),

    CSV_IMPORT("Bulk import",
            "Load a whole department's courses, rooms and staff from a spreadsheet."),

    EXPORT("Export",
            "Download any timetable as CSV or a printable page."),

    API_ACCESS("API access",
            "Read and write timetables from your own systems with an API key."),

    WEBHOOKS("Webhooks",
            "Get a callback whenever a class is scheduled, moved or cleared."),

    LMS_SYNC("Learning platform sync",
            "Push timetables into Canvas, Moodle or Blackboard."),

    AUDIT_LOG("Audit log",
            "See who changed which class, and when."),

    CUSTOM_BRANDING("Custom branding",
            "Your institution's name and colour on public pages."),

    UNLIMITED_STAFF("Unlimited staff",
            "No cap on how many lecturers you can add."),

    PRIORITY_SUPPORT("Priority support",
            "Replies within one working day.");

    private final String label;
    private final String description;

    Feature(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}

package com.chalkline.service;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.TimetableEntry;
import com.chalkline.domain.User;
import com.chalkline.support.ScheduleGrid;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Turns a timetable into an iCalendar feed.
 *
 * This is the integration that covers the most ground for the least work:
 * Outlook, Google Calendar, Apple Calendar and almost everything else can
 * subscribe to an .ics URL, with no OAuth application to register with each
 * vendor and nothing to re-approve when a token expires. One URL, every
 * calendar.
 *
 * Times are written as FLOATING local time -- no timezone and no Z suffix.
 * For a timetable that is what people mean: a 09:00 class is at 09:00 where
 * the institution is, and it does not move when the clocks change or when a
 * lecturer opens their calendar from another country.
 */
@Service
public class CalendarFeedService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    /** iCalendar requires CRLF line endings, not plain newlines. */
    private static final String CRLF = "\r\n";

    public String buildFeed(Organisation organisation, String calendarName, List<TimetableEntry> entries) {
        ScheduleGrid grid = ScheduleGrid.of(organisation);
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);

        StringBuilder ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//Chalkline//Timetable//EN");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "METHOD:PUBLISH");
        line(ics, "X-WR-CALNAME:" + escape(calendarName));
        // Tells Outlook and Google how often to re-read the feed.
        line(ics, "REFRESH-INTERVAL;VALUE=DURATION:PT12H");
        line(ics, "X-PUBLISHED-TTL:PT12H");

        for (TimetableEntry entry : entries) {
            String start = grid.startTime(entry.getSlotIndex());
            String end = grid.endTime(entry.getSlotIndex());
            if (start == null || end == null) {
                // A slot label that is not a time range cannot become an
                // event. Skipped rather than guessed at.
                continue;
            }

            LocalDate firstDate = nextOccurrence(grid.dayName(entry.getDayIndex()), entry.getDayIndex());

            line(ics, "BEGIN:VEVENT");
            line(ics, "UID:entry-" + entry.getId() + "-" + organisation.getSlug() + "@chalkline");
            line(ics, "DTSTAMP:" + stamp);
            line(ics, "DTSTART:" + firstDate.atTime(hour(start), minute(start)).format(LOCAL));
            line(ics, "DTEND:" + firstDate.atTime(hour(end), minute(end)).format(LOCAL));
            line(ics, "RRULE:FREQ=WEEKLY");
            line(ics, "SUMMARY:" + escape(entry.getCourse().getCode() + " " + entry.getCourse().getName()));
            line(ics, "LOCATION:" + escape(entry.getRoom().getName()));
            line(ics, "DESCRIPTION:" + escape(
                    "Lecturer: " + entry.getLecturer().getDisplayName()
                            + "\\nRoom: " + entry.getRoom().getLabel()
                            + "\\nTimetable by Chalkline"));
            line(ics, "END:VEVENT");
        }

        line(ics, "END:VCALENDAR");
        return ics.toString();
    }

    public String personalCalendarName(User lecturer) {
        return lecturer.getDisplayName() + " - " + lecturer.getOrganisation().getName();
    }

    /**
     * The next date matching the column's day name, so the recurring series
     * starts in the current or coming week rather than in the past.
     */
    private LocalDate nextOccurrence(String dayName, int fallbackIndex) {
        DayOfWeek target = parseDay(dayName, fallbackIndex);
        LocalDate date = LocalDate.now();
        while (date.getDayOfWeek() != target) {
            date = date.plusDays(1);
        }
        return date;
    }

    /** Day columns are free text, so fall back to position when unrecognised. */
    private DayOfWeek parseDay(String dayName, int fallbackIndex) {
        if (dayName != null) {
            for (DayOfWeek candidate : DayOfWeek.values()) {
                String full = candidate.getDisplayName(TextStyle.FULL, Locale.UK);
                if (full.equalsIgnoreCase(dayName.trim())) {
                    return candidate;
                }
            }
        }
        return DayOfWeek.of(Math.floorMod(fallbackIndex, 7) + 1);
    }

    private int hour(String time) {
        return Integer.parseInt(time.split(":")[0]);
    }

    private int minute(String time) {
        return Integer.parseInt(time.split(":")[1]);
    }

    private void line(StringBuilder sb, String value) {
        sb.append(value).append(CRLF);
    }

    /** Commas, semicolons and backslashes are structural in iCalendar. */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}

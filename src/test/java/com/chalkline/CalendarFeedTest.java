package com.chalkline;

import com.chalkline.service.CalendarFeedService;
import com.chalkline.service.TimetableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar feed is the integration that reaches Outlook, Google and Apple
 * without registering an application with any of them, so it is worth checking
 * the file it produces is actually well formed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:calendar;DB_CLOSE_DELAY=-1",
        "app.demo.email="
})
@Import(TestFixtures.class)
@Transactional
class CalendarFeedTest {

    @Autowired TestFixtures fixtures;
    @Autowired TimetableService timetableService;
    @Autowired CalendarFeedService calendarFeedService;

    TestFixtures.Fixture uni;

    @BeforeEach
    void setUp() {
        uni = fixtures.institution("Calendar College", "cal.edu");
        // Monday, 08:00 - 09:00
        timetableService.saveEntry(uni.ada(), uni.ada().getEmail(), 0, 0,
                uni.maths().getId(), uni.lab1().getId());
    }

    private String feed() {
        return calendarFeedService.buildFeed(uni.organisation(),
                calendarFeedService.personalCalendarName(uni.ada()),
                timetableService.entriesFor(uni.ada()));
    }

    @Test
    @DisplayName("the feed is a well formed weekly recurring calendar")
    void feedIsWellFormed() {
        String ics = feed();

        assertThat(ics)
                .startsWith("BEGIN:VCALENDAR")
                .endsWith("END:VCALENDAR\r\n")
                .contains("VERSION:2.0")
                .contains("BEGIN:VEVENT")
                .contains("END:VEVENT")
                .contains("RRULE:FREQ=WEEKLY")
                .contains("SUMMARY:MTH110 Discrete Mathematics")
                .contains("LOCATION:Lab 1");
    }

    @Test
    @DisplayName("lines end with CRLF, as iCalendar requires")
    void usesCrlfLineEndings() {
        String ics = feed();

        // Every newline must be preceded by a carriage return.
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    @DisplayName("times come from the slot label and the event starts on the right weekday")
    void timesAreTakenFromTheSlotLabel() {
        String ics = feed();

        assertThat(ics).containsPattern("DTSTART:\\d{8}T080000");
        assertThat(ics).containsPattern("DTEND:\\d{8}T090000");

        // No Z and no TZID: floating local time, so 08:00 stays 08:00 wherever
        // the calendar is opened and does not shift when the clocks change.
        assertThat(ics).doesNotContain("DTSTART;TZID").doesNotContain("T080000Z");
    }

    @Test
    @DisplayName("a slot label that is not a time range is skipped rather than guessed at")
    void nonTimeSlotLabelsAreSkipped() {
        uni.organisation().setScheduleSlots("Morning,Afternoon");

        String ics = calendarFeedService.buildFeed(uni.organisation(), "Test",
                timetableService.entriesFor(uni.ada()));

        assertThat(ics).contains("BEGIN:VCALENDAR").doesNotContain("BEGIN:VEVENT");
    }

    @Test
    @DisplayName("commas and semicolons in names are escaped, not left to break the file")
    void specialCharactersAreEscaped() {
        String ics = calendarFeedService.buildFeed(uni.organisation(),
                "Ada Lovelace, PhD; Mathematics",
                timetableService.entriesFor(uni.ada()));

        assertThat(ics).contains("X-WR-CALNAME:Ada Lovelace\\, PhD\\; Mathematics");
    }
}

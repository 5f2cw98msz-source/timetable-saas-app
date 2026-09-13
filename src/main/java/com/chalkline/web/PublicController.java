package com.chalkline.web;

import com.chalkline.domain.Feature;
import com.chalkline.domain.ShareLink;
import com.chalkline.domain.User;
import com.chalkline.repo.UserRepository;
import com.chalkline.service.CalendarFeedService;
import com.chalkline.service.ShareLinkService;
import com.chalkline.service.TimetableService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Pages for people with no account: students looking at a shared timetable,
 * and calendar apps fetching a subscription feed.
 *
 * Both are protected by an unguessable token in the URL rather than a login,
 * because the audience has no account to sign in with. Tokens are 24 random
 * bytes and can be regenerated, which is what makes that acceptable.
 */
@Controller
public class PublicController {

    private final ShareLinkService shareLinkService;
    private final TimetableService timetableService;
    private final CalendarFeedService calendarFeedService;
    private final UserRepository users;

    public PublicController(ShareLinkService shareLinkService,
                            TimetableService timetableService,
                            CalendarFeedService calendarFeedService,
                            UserRepository users) {
        this.shareLinkService = shareLinkService;
        this.timetableService = timetableService;
        this.calendarFeedService = calendarFeedService;
        this.users = users;
    }

    /** The read-only page students open. */
    @GetMapping("/s/{token}")
    public String sharedTimetable(@PathVariable String token, Model model) {
        Optional<ShareLink> link = shareLinkService.resolveForPublicView(token);
        if (link.isEmpty()) {
            return "public/not-found";
        }

        ShareLink shareLink = link.get();
        model.addAttribute("organisation", shareLink.getOrganisation());
        model.addAttribute("shareLink", shareLink);
        model.addAttribute("grid", timetableService.buildPublicGrid(shareLink));
        return "public/timetable";
    }

    /** One lecturer's personal calendar subscription. */
    @GetMapping("/feed/lecturer/{token}.ics")
    public ResponseEntity<byte[]> lecturerFeed(@PathVariable String token) {
        Optional<User> lecturer = users.findByCalendarToken(token)
                .filter(User::isEnabled)
                .filter(user -> user.getOrganisation().hasFeature(Feature.CALENDAR_FEED));

        if (lecturer.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = lecturer.get();

        String ics = calendarFeedService.buildFeed(
                user.getOrganisation(),
                calendarFeedService.personalCalendarName(user),
                timetableService.entriesFor(user));

        return calendar(ics, user.getOrganisation().getSlug() + "-" + user.getId() + ".ics");
    }

    /** A shared timetable as a calendar feed, for the same token as the page. */
    @GetMapping("/feed/shared/{token}.ics")
    public ResponseEntity<byte[]> sharedFeed(@PathVariable String token) {
        Optional<ShareLink> link = shareLinkService.resolveForPublicView(token);
        if (link.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ShareLink shareLink = link.get();
        var entries = shareLink.getScope() == ShareLink.Scope.SINGLE_LECTURER
                && shareLink.getLecturer() != null
                ? timetableService.entriesFor(shareLink.getLecturer())
                : timetableService.entriesFor(shareLink.getOrganisation());

        String ics = calendarFeedService.buildFeed(
                shareLink.getOrganisation(), shareLink.getLabel(), entries);

        return calendar(ics, shareLink.getOrganisation().getSlug() + "-shared.ics");
    }

    private ResponseEntity<byte[]> calendar(String ics, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                // Calendar clients poll this; a short cache keeps that cheap
                // without making a change take long to appear.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"))
                .body(ics.getBytes(StandardCharsets.UTF_8));
    }
}

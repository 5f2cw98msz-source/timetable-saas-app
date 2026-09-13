package com.chalkline.web;

import com.chalkline.domain.*;
import com.chalkline.security.AppUserPrincipal;
import com.chalkline.service.*;
import com.chalkline.support.ScheduleGrid;
import com.chalkline.web.form.Forms;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The weekly grid: viewing it, and adding or clearing one class at a time. */
@Controller
public class TimetableController {

    private final TimetableService timetableService;
    private final CatalogService catalogService;
    private final UserService userService;
    private final ExportService exportService;
    private final PlanPolicy planPolicy;
    private final CurrentUser currentUser;

    public TimetableController(TimetableService timetableService,
                               CatalogService catalogService,
                               UserService userService,
                               ExportService exportService,
                               PlanPolicy planPolicy,
                               CurrentUser currentUser) {
        this.timetableService = timetableService;
        this.catalogService = catalogService;
        this.userService = userService;
        this.exportService = exportService;
        this.planPolicy = planPolicy;
        this.currentUser = currentUser;
    }

    @GetMapping("/timetable")
    public String timetable(@AuthenticationPrincipal AppUserPrincipal principal,
                            @RequestParam(required = false) String lecturer,
                            Model model) {

        User actingUser = currentUser.require(principal);
        Organisation organisation = actingUser.getOrganisation();

        model.addAttribute("grid", timetableService.buildGrid(actingUser, lecturer));
        model.addAttribute("organisation", organisation);
        model.addAttribute("canExport", organisation.hasFeature(Feature.EXPORT));
        model.addAttribute("calendarToken", actingUser.getCalendarToken());
        model.addAttribute("hasCalendarFeed", organisation.hasFeature(Feature.CALENDAR_FEED));
        return "app/timetable";
    }

    @GetMapping("/timetable/slot")
    public String slotForm(@AuthenticationPrincipal AppUserPrincipal principal,
                           @RequestParam int day,
                           @RequestParam int slot,
                           @RequestParam(required = false) String lecturer,
                           Model model,
                           RedirectAttributes redirectAttributes) {

        User actingUser = currentUser.require(principal);
        Organisation organisation = actingUser.getOrganisation();
        String target = timetableService.resolveEditTarget(actingUser, lecturer);

        if (target == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Choose a lecturer before adding a class.");
            return "redirect:/timetable";
        }

        // Runs before anything is rendered, so a lecturer reaching for someone
        // else's slot is refused here rather than quietly handed their own.
        Optional<TimetableEntry> existing = timetableService.findEntry(actingUser, target, day, slot);

        if (catalogService.findCourses(organisation).isEmpty()
                || catalogService.findRooms(organisation).isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", nothingToScheduleMessage(actingUser));
            return "redirect:" + timetableUrl(target);
        }

        ScheduleGrid grid = ScheduleGrid.of(organisation);
        Forms.Slot form = new Forms.Slot();
        form.setDay(day);
        form.setSlot(slot);
        form.setLecturer(target);
        existing.ifPresent(entry -> {
            form.setCourseId(entry.getCourse().getId());
            form.setRoomId(entry.getRoom().getId());
        });

        model.addAttribute("form", form);
        model.addAttribute("existing", existing.orElse(null));
        model.addAttribute("courses", catalogService.findCourses(organisation));
        model.addAttribute("rooms", catalogService.findRooms(organisation));
        model.addAttribute("dayName", grid.dayName(day));
        model.addAttribute("slotName", grid.slotName(slot));
        model.addAttribute("lecturerName", userService.requireMemberByEmail(target, organisation).getDisplayName());
        model.addAttribute("backUrl", timetableUrl(target));
        return "app/slot";
    }

    @PostMapping("/timetable/slot")
    public String saveSlot(@AuthenticationPrincipal AppUserPrincipal principal,
                           @ModelAttribute("form") Forms.Slot form,
                           RedirectAttributes redirectAttributes) {

        User actingUser = currentUser.require(principal);
        ScheduleGrid grid = ScheduleGrid.of(actingUser.getOrganisation());

        timetableService.saveEntry(actingUser, form.getLecturer(), form.getDay(), form.getSlot(),
                form.getCourseId(), form.getRoomId());

        redirectAttributes.addFlashAttribute("successMessage",
                "Class saved for " + grid.dayName(form.getDay()) + ", " + grid.slotName(form.getSlot()) + ".");
        return "redirect:" + timetableUrl(form.getLecturer());
    }

    @PostMapping("/timetable/slot/clear")
    public String clearSlot(@AuthenticationPrincipal AppUserPrincipal principal,
                            @RequestParam int day,
                            @RequestParam int slot,
                            @RequestParam String lecturer,
                            RedirectAttributes redirectAttributes) {

        User actingUser = currentUser.require(principal);
        ScheduleGrid grid = ScheduleGrid.of(actingUser.getOrganisation());

        if (timetableService.clearEntry(actingUser, lecturer, day, slot)) {
            redirectAttributes.addFlashAttribute("successMessage",
                    "Class cleared from " + grid.dayName(day) + ", " + grid.slotName(slot) + ".");
        }
        return "redirect:" + timetableUrl(lecturer);
    }

    /** CSV of whatever the user is allowed to see. Premium. */
    @GetMapping("/timetable/export.csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AppUserPrincipal principal,
                                         @RequestParam(required = false) String lecturer) {

        User actingUser = currentUser.require(principal);
        Organisation organisation = actingUser.getOrganisation();
        planPolicy.require(organisation, Feature.EXPORT);

        String target = timetableService.resolveTarget(actingUser, lecturer);
        var entries = target == null
                ? timetableService.entriesFor(organisation)
                : timetableService.entriesFor(userService.requireMemberByEmail(target, organisation));

        byte[] body = exportService.toCsv(organisation, entries).getBytes(StandardCharsets.UTF_8);
        String filename = organisation.getSlug() + "-timetable.csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    private String nothingToScheduleMessage(User actingUser) {
        Organisation organisation = actingUser.getOrganisation();
        boolean noCourses = catalogService.findCourses(organisation).isEmpty();
        boolean noRooms = catalogService.findRooms(organisation).isEmpty();

        String missing = noCourses && noRooms ? "courses or rooms" : noCourses ? "courses" : "rooms";
        String hint = actingUser.isAdmin()
                ? "Add some under Settings, Rooms and courses."
                : "Ask an administrator to add some first.";
        return "No " + missing + " have been set up yet. " + hint;
    }

    private String timetableUrl(String lecturer) {
        return UriComponentsBuilder.fromPath("/timetable")
                .queryParam("lecturer", lecturer)
                .toUriString();
    }
}

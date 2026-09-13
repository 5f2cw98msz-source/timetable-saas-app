package com.chalkline.api;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.TimetableEntry;
import com.chalkline.service.ApiKeyService;
import com.chalkline.service.CatalogService;
import com.chalkline.service.TimetableService;
import com.chalkline.service.UserService;
import com.chalkline.support.ScheduleGrid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The read API.
 *
 * Authenticated with an API key rather than a session, so other systems can
 * call it: "Authorization: Bearer ck_...". The key identifies the
 * organisation, and every response is built from that organisation alone --
 * there is no way to name a different one in a request.
 *
 * Read-only for now. Writing a timetable from outside needs conflict rules
 * that are worth designing properly rather than bolting on.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final ApiKeyService apiKeyService;
    private final TimetableService timetableService;
    private final CatalogService catalogService;
    private final UserService userService;

    public ApiController(ApiKeyService apiKeyService,
                         TimetableService timetableService,
                         CatalogService catalogService,
                         UserService userService) {
        this.apiKeyService = apiKeyService;
        this.timetableService = timetableService;
        this.catalogService = catalogService;
        this.userService = userService;
    }

    @GetMapping("/timetable")
    public ResponseEntity<?> timetable(@RequestHeader(value = "Authorization", required = false) String auth) {
        Organisation organisation = authenticate(auth);
        if (organisation == null) {
            return unauthorised();
        }

        ScheduleGrid grid = ScheduleGrid.of(organisation);
        List<Map<String, Object>> classes = timetableService.entriesFor(organisation).stream()
                .map(entry -> describe(entry, grid))
                .toList();

        return ResponseEntity.ok(Map.of(
                "organisation", organisation.getName(),
                "days", grid.days(),
                "slots", grid.slots(),
                "classes", classes));
    }

    @GetMapping("/courses")
    public ResponseEntity<?> courses(@RequestHeader(value = "Authorization", required = false) String auth) {
        Organisation organisation = authenticate(auth);
        if (organisation == null) {
            return unauthorised();
        }
        return ResponseEntity.ok(Map.of("courses", catalogService.findCourses(organisation).stream()
                .map(course -> Map.of(
                        "code", course.getCode(),
                        "name", course.getName(),
                        "colour", course.getColour()))
                .toList()));
    }

    @GetMapping("/rooms")
    public ResponseEntity<?> rooms(@RequestHeader(value = "Authorization", required = false) String auth) {
        Organisation organisation = authenticate(auth);
        if (organisation == null) {
            return unauthorised();
        }
        return ResponseEntity.ok(Map.of("rooms", catalogService.findRooms(organisation).stream()
                .map(room -> Map.of(
                        "name", room.getName(),
                        "capacity", room.getCapacity()))
                .toList()));
    }

    @GetMapping("/lecturers")
    public ResponseEntity<?> lecturers(@RequestHeader(value = "Authorization", required = false) String auth) {
        Organisation organisation = authenticate(auth);
        if (organisation == null) {
            return unauthorised();
        }
        return ResponseEntity.ok(Map.of("lecturers", userService.findLecturers(organisation).stream()
                .map(user -> Map.of(
                        "name", user.getDisplayName(),
                        "email", user.getEmail()))
                .toList()));
    }

    private Map<String, Object> describe(TimetableEntry entry, ScheduleGrid grid) {
        return Map.of(
                "day", grid.dayName(entry.getDayIndex()),
                "slot", grid.slotName(entry.getSlotIndex()),
                "courseCode", entry.getCourse().getCode(),
                "courseName", entry.getCourse().getName(),
                "room", entry.getRoom().getName(),
                "lecturer", entry.getLecturer().getDisplayName(),
                "lecturerEmail", entry.getLecturer().getEmail());
    }

    /** @return the organisation the key belongs to, or null if it is not valid. */
    private Organisation authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return apiKeyService.authenticate(authorizationHeader.substring("Bearer ".length()))
                .orElse(null);
    }

    private ResponseEntity<Map<String, String>> unauthorised() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error",
                        "Send a valid API key as 'Authorization: Bearer ck_...'. "
                                + "Keys are created under Settings, API keys, on the Premium plan."));
    }
}

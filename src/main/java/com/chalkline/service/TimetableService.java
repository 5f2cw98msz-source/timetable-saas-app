package com.chalkline.service;

import com.chalkline.domain.*;
import com.chalkline.repo.*;
import com.chalkline.support.ScheduleGrid;
import com.chalkline.web.view.GridView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Reading and editing the weekly grid.
 *
 * Timetables are PERSONAL, as in the original desktop app: a lecturer sees and
 * edits only their own, and an admin either edits one named lecturer's grid or
 * reads a combined overview of everybody's.
 *
 * Those rules are enforced here, on the server. A browser can send any form it
 * likes, so "the button was hidden" is not a control.
 */
@Service
public class TimetableService {

    /** Value of the lecturer parameter meaning "show everyone at once". */
    public static final String COMBINED = "all";

    private final TimetableEntryRepository entries;
    private final UserRepository users;
    private final CourseRepository courses;
    private final RoomRepository rooms;
    private final AuditService audit;
    private final WebhookService webhooks;

    public TimetableService(TimetableEntryRepository entries,
                            UserRepository users,
                            CourseRepository courses,
                            RoomRepository rooms,
                            AuditService audit,
                            WebhookService webhooks) {
        this.entries = entries;
        this.users = users;
        this.courses = courses;
        this.rooms = rooms;
        this.audit = audit;
        this.webhooks = webhooks;
    }

    /**
     * Which timetable a VIEW should show. A lecturer always gets their own,
     * whatever the URL asks for; an admin gets the lecturer they named, or the
     * combined view when they named nobody.
     */
    public String resolveTarget(User actingUser, String requestedLecturer) {
        if (actingUser.getRole() == Role.LECTURER) {
            return actingUser.getEmail();
        }
        if (requestedLecturer == null || requestedLecturer.isBlank()
                || COMBINED.equalsIgnoreCase(requestedLecturer)) {
            return null;
        }
        return User.normaliseEmail(requestedLecturer);
    }

    /**
     * Which timetable an EDIT applies to.
     *
     * Deliberately different from resolveTarget: viewing somebody else's grid
     * quietly falls back to your own, but editing must not. Silently
     * retargeting an edit would write the class to the wrong lecturer, so the
     * name is passed through and requireEditable refuses it.
     */
    public String resolveEditTarget(User actingUser, String requestedLecturer) {
        if (requestedLecturer == null || requestedLecturer.isBlank()
                || COMBINED.equalsIgnoreCase(requestedLecturer)) {
            return actingUser.getRole() == Role.LECTURER ? actingUser.getEmail() : null;
        }
        return User.normaliseEmail(requestedLecturer);
    }

    @Transactional(readOnly = true)
    public GridView buildGrid(User actingUser, String requestedLecturer) {
        Organisation organisation = actingUser.getOrganisation();
        ScheduleGrid grid = ScheduleGrid.of(organisation);

        String target = resolveTarget(actingUser, requestedLecturer);
        boolean combined = target == null;

        User lecturer = null;
        List<TimetableEntry> found;
        if (combined) {
            found = entries.findByOrganisationOrderByDayIndexAscSlotIndexAsc(organisation);
        } else {
            lecturer = users.findByEmailAndOrganisation(target, organisation)
                    .orElseThrow(() -> new ValidationException(
                            "No account for " + requestedLecturer + " in your organisation."));
            found = entries.findByLecturerOrderByDayIndexAscSlotIndexAsc(lecturer);
        }

        return assemble(grid, found, combined,
                combined ? null : lecturer.getEmail(),
                scopeLabel(actingUser, lecturer, combined),
                scopeHint(actingUser, lecturer, combined),
                !combined,
                lecturerOptions(actingUser, organisation, target));
    }

    /** The same grid, for a public share link -- always read-only. */
    @Transactional(readOnly = true)
    public GridView buildPublicGrid(ShareLink link) {
        Organisation organisation = link.getOrganisation();
        ScheduleGrid grid = ScheduleGrid.of(organisation);

        List<TimetableEntry> found;
        String label;
        if (link.getScope() == ShareLink.Scope.SINGLE_LECTURER && link.getLecturer() != null) {
            found = entries.findByLecturerOrderByDayIndexAscSlotIndexAsc(link.getLecturer());
            label = link.getLecturer().getDisplayName();
        } else {
            found = entries.findByOrganisationOrderByDayIndexAscSlotIndexAsc(organisation);
            label = organisation.getName();
        }

        boolean combined = link.getScope() == ShareLink.Scope.WHOLE_ORGANISATION;
        return assemble(grid, found, combined, null, label, null, false, List.of());
    }

    private GridView assemble(ScheduleGrid grid, List<TimetableEntry> found, boolean combined,
                              String selected, String scopeLabel, String scopeHint,
                              boolean editable, List<GridView.LecturerOption> options) {

        Map<Long, List<GridView.EntryView>> byCell = new HashMap<>();
        for (TimetableEntry entry : found) {
            byCell.computeIfAbsent(cellKey(entry.getDayIndex(), entry.getSlotIndex()), k -> new ArrayList<>())
                    .add(toEntryView(entry));
        }

        List<GridView.RowView> rows = new ArrayList<>(grid.slotCount());
        for (int slot = 0; slot < grid.slotCount(); slot++) {
            List<GridView.CellView> cells = new ArrayList<>(grid.dayCount());
            for (int day = 0; day < grid.dayCount(); day++) {
                cells.add(new GridView.CellView(day, slot,
                        byCell.getOrDefault(cellKey(day, slot), List.of())));
            }
            rows.add(new GridView.RowView(slot, grid.slotName(slot), cells));
        }

        return new GridView(scopeLabel, scopeHint, editable, selected, combined,
                options, grid.days(), rows, found.size());
    }

    @Transactional(readOnly = true)
    public Optional<TimetableEntry> findEntry(User actingUser, String targetEmail, int day, int slot) {
        User lecturer = requireEditable(actingUser, targetEmail);
        return entries.findByDayIndexAndSlotIndexAndLecturer(day, slot, lecturer);
    }

    @Transactional(readOnly = true)
    public List<TimetableEntry> entriesFor(User lecturer) {
        return entries.findByLecturerOrderByDayIndexAscSlotIndexAsc(lecturer);
    }

    @Transactional(readOnly = true)
    public List<TimetableEntry> entriesFor(Organisation organisation) {
        return entries.findByOrganisationOrderByDayIndexAscSlotIndexAsc(organisation);
    }

    /** Creates the class in this cell, or replaces whatever was already there. */
    @Transactional
    public TimetableEntry saveEntry(User actingUser, String targetEmail,
                                    int day, int slot, Long courseId, Long roomId) {

        Organisation organisation = actingUser.getOrganisation();
        ScheduleGrid grid = ScheduleGrid.of(organisation);

        User lecturer = requireEditable(actingUser, targetEmail);
        requireValidCell(grid, day, slot);

        Course course = courses.findByIdAndOrganisation(courseId, organisation)
                .orElseThrow(() -> new ValidationException("Pick a course from the list."));
        Room room = rooms.findByIdAndOrganisation(roomId, organisation)
                .orElseThrow(() -> new ValidationException("Pick a room from the list."));

        if (organisation.isPreventRoomClashes()) {
            requireRoomFree(organisation, grid, day, slot, room, lecturer);
        }

        TimetableEntry entry = entries.findByDayIndexAndSlotIndexAndLecturer(day, slot, lecturer)
                .orElseGet(() -> new TimetableEntry(day, slot, course, room, lecturer));
        entry.setCourse(course);
        entry.setRoom(room);
        entries.save(entry);

        String detail = course.getCode() + " in " + room.getName() + " for " + lecturer.getDisplayName()
                + " on " + grid.dayName(day) + " " + grid.slotName(slot);
        audit.record(organisation, actingUser, AuditService.SCHEDULED, detail);
        webhooks.notifyTimetableChanged(organisation, "class.scheduled", detail);
        return entry;
    }

    /** Empties this cell. Doing so when it is already empty is not an error. */
    @Transactional
    public boolean clearEntry(User actingUser, String targetEmail, int day, int slot) {
        Organisation organisation = actingUser.getOrganisation();
        ScheduleGrid grid = ScheduleGrid.of(organisation);

        User lecturer = requireEditable(actingUser, targetEmail);
        requireValidCell(grid, day, slot);

        Optional<TimetableEntry> existing =
                entries.findByDayIndexAndSlotIndexAndLecturer(day, slot, lecturer);
        if (existing.isEmpty()) {
            return false;
        }

        TimetableEntry entry = existing.get();
        String detail = entry.getCourse().getCode() + " for " + lecturer.getDisplayName()
                + " on " + grid.dayName(day) + " " + grid.slotName(slot);
        entries.delete(entry);

        audit.record(organisation, actingUser, AuditService.CLEARED, detail);
        webhooks.notifyTimetableChanged(organisation, "class.cleared", detail);
        return true;
    }

    // ---- rules ----

    /**
     * The authorisation check, in one place. A lecturer may only ever touch
     * their own timetable; an admin may touch any lecturer's, within their own
     * organisation and no other.
     */
    private User requireEditable(User actingUser, String targetEmail) {
        if (targetEmail == null || targetEmail.isBlank()) {
            throw new ValidationException("Choose a lecturer before adding or clearing a class.");
        }
        String target = User.normaliseEmail(targetEmail);

        if (actingUser.getRole() == Role.LECTURER && !target.equals(actingUser.getEmail())) {
            throw new AccessDeniedForLecturerException("Lecturers can only change their own timetable.");
        }

        // Scoped to the acting user's organisation, so an id or email from
        // another tenant is simply not found.
        User lecturer = users.findByEmailAndOrganisation(target, actingUser.getOrganisation())
                .orElseThrow(() -> new ValidationException(
                        "No account for " + targetEmail + " in your organisation."));

        if (lecturer.getRole() != Role.LECTURER && !lecturer.getId().equals(actingUser.getId())) {
            throw new ValidationException(lecturer.getDisplayName()
                    + " is an administrator, not a lecturer, so has no teaching timetable.");
        }
        return lecturer;
    }

    /** Two lecturers cannot hold the same room in the same day and time slot. */
    private void requireRoomFree(Organisation organisation, ScheduleGrid grid,
                                 int day, int slot, Room room, User lecturer) {

        for (TimetableEntry other : entries.findByOrganisationAndDayIndexAndSlotIndex(organisation, day, slot)) {
            boolean sameLecturer = other.getLecturer().getId().equals(lecturer.getId());
            if (!sameLecturer && other.getRoom().getId().equals(room.getId())) {
                throw new ValidationException(room.getName() + " is already booked by "
                        + other.getLecturer().getDisplayName() + " on "
                        + grid.dayName(day) + " at " + grid.slotName(slot) + ".");
            }
        }
    }

    private void requireValidCell(ScheduleGrid grid, int day, int slot) {
        if (!grid.isValidDay(day) || !grid.isValidSlot(slot)) {
            throw new ValidationException("That day or time slot is not part of your timetable.");
        }
    }

    // ---- view helpers ----

    private List<GridView.LecturerOption> lecturerOptions(User actingUser, Organisation organisation,
                                                          String selected) {
        if (actingUser.getRole() != Role.ADMIN) {
            return List.of();
        }
        List<GridView.LecturerOption> options = new ArrayList<>();
        for (User lecturer : users.findByOrganisationAndRoleOrderByDisplayNameAsc(organisation, Role.LECTURER)) {
            options.add(new GridView.LecturerOption(
                    lecturer.getEmail(),
                    lecturer.getDisplayName() + (lecturer.isEnabled() ? "" : " (suspended)"),
                    lecturer.getEmail().equals(selected)));
        }
        return options;
    }

    private String scopeLabel(User actingUser, User lecturer, boolean combined) {
        if (actingUser.getRole() == Role.LECTURER) {
            return "Your timetable";
        }
        return combined ? "All lecturers" : lecturer.getDisplayName() + "'s timetable";
    }

    private String scopeHint(User actingUser, User lecturer, boolean combined) {
        if (actingUser.getRole() == Role.LECTURER) {
            return "This is your personal timetable. Only you and an administrator can see it.";
        }
        if (combined) {
            return "Combined overview, read-only. Choose a lecturer to add or clear their classes.";
        }
        return "You are editing " + lecturer.getDisplayName()
                + "'s personal timetable. Changes affect only their grid.";
    }

    private GridView.EntryView toEntryView(TimetableEntry entry) {
        Course course = entry.getCourse();
        Room room = entry.getRoom();

        String warning = null;
        if (course.getExpectedStudents() != null && room.getCapacity() > 0
                && course.getExpectedStudents() > room.getCapacity()) {
            warning = room.getName() + " seats " + room.getCapacity()
                    + ", but " + course.getCode() + " expects " + course.getExpectedStudents() + ".";
        }

        return new GridView.EntryView(
                entry.getId(),
                course.getCode(),
                course.getName(),
                course.getColour(),
                room.getName(),
                entry.getLecturer().getDisplayName(),
                entry.getLecturer().getEmail(),
                warning);
    }

    private Long cellKey(int day, int slot) {
        return (long) day * 1000L + slot;
    }
}

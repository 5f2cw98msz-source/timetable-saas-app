package com.chalkline.service;

import com.chalkline.domain.*;
import com.chalkline.repo.CourseRepository;
import com.chalkline.repo.RoomRepository;
import com.chalkline.repo.TimetableEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The rooms and courses an organisation can schedule into.
 *
 * Deleting one that is still timetabled is refused rather than silently
 * leaving classes pointing at something that no longer exists.
 */
@Service
public class CatalogService {

    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TimetableEntryRepository entries;
    private final AuditService audit;

    public CatalogService(RoomRepository rooms,
                          CourseRepository courses,
                          TimetableEntryRepository entries,
                          AuditService audit) {
        this.rooms = rooms;
        this.courses = courses;
        this.entries = entries;
        this.audit = audit;
    }

    // ---- Rooms ----

    @Transactional(readOnly = true)
    public List<Room> findRooms(Organisation organisation) {
        return rooms.findByOrganisationOrderByNameAsc(organisation);
    }

    @Transactional
    public Room addRoom(Organisation organisation, String name, Integer capacity,
                        String facilities, User actor) {

        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("Room name cannot be empty.");
        }
        if (capacity != null && capacity < 0) {
            throw new ValidationException("Capacity cannot be negative.");
        }
        if (rooms.findByOrganisationAndNameIgnoreCase(organisation, trimmed).isPresent()) {
            throw new ValidationException("A room called \"" + trimmed + "\" already exists.");
        }

        Room room = new Room(organisation, trimmed, capacity == null ? 0 : capacity);
        room.setFacilities(facilities == null || facilities.isBlank() ? null : facilities.trim());
        rooms.save(room);
        audit.record(organisation, actor, AuditService.CATALOG_CHANGED, "Room added: " + trimmed);
        return room;
    }

    @Transactional
    public void deleteRoom(Long id, Organisation organisation, User actor) {
        Room room = rooms.findByIdAndOrganisation(id, organisation)
                .orElseThrow(() -> new ValidationException("That room is not part of your organisation."));

        long inUse = entries.countByRoom(room);
        if (inUse > 0) {
            throw new ValidationException("\"" + room.getName() + "\" is used by " + inUse
                    + (inUse == 1 ? " scheduled class" : " scheduled classes")
                    + ". Clear those first, then delete the room.");
        }

        rooms.delete(room);
        audit.record(organisation, actor, AuditService.CATALOG_CHANGED, "Room deleted: " + room.getName());
    }

    // ---- Courses ----

    @Transactional(readOnly = true)
    public List<Course> findCourses(Organisation organisation) {
        return courses.findByOrganisationOrderByCodeAsc(organisation);
    }

    @Transactional
    public Course addCourse(Organisation organisation, String code, String name,
                            Integer expectedStudents, User actor) {

        String trimmedCode = code == null ? "" : code.trim();
        String trimmedName = name == null ? "" : name.trim();

        if (trimmedCode.isEmpty() || trimmedName.isEmpty()) {
            throw new ValidationException("Both a course code and a name are required.");
        }
        if (courses.findByOrganisationAndCodeIgnoreCase(organisation, trimmedCode).isPresent()) {
            throw new ValidationException("A course with code \"" + trimmedCode + "\" already exists.");
        }

        Course course = new Course(organisation, trimmedCode, trimmedName);
        course.setExpectedStudents(expectedStudents != null && expectedStudents > 0 ? expectedStudents : null);
        courses.save(course);
        audit.record(organisation, actor, AuditService.CATALOG_CHANGED, "Course added: " + trimmedCode);
        return course;
    }

    @Transactional
    public void deleteCourse(Long id, Organisation organisation, User actor) {
        Course course = courses.findByIdAndOrganisation(id, organisation)
                .orElseThrow(() -> new ValidationException("That course is not part of your organisation."));

        long inUse = entries.countByCourse(course);
        if (inUse > 0) {
            throw new ValidationException("\"" + course.getCode() + "\" is used by " + inUse
                    + (inUse == 1 ? " scheduled class" : " scheduled classes")
                    + ". Clear those first, then delete the course.");
        }

        courses.delete(course);
        audit.record(organisation, actor, AuditService.CATALOG_CHANGED, "Course deleted: " + course.getCode());
    }

    // ---- Bulk import (Premium) ----

    /**
     * Loads courses or rooms from pasted spreadsheet rows. Skips anything that
     * already exists rather than failing the whole import, and reports back
     * what it did -- re-importing a corrected file is then safe.
     */
    @Transactional
    public ImportResult importCourses(Organisation organisation, String csv, User actor) {
        List<String> skipped = new ArrayList<>();
        int added = 0;

        for (String line : csv.split("\\r?\\n")) {
            String[] cells = line.split(",", 3);
            if (cells.length < 2 || cells[0].isBlank()) {
                continue;
            }
            String code = cells[0].trim();
            String name = cells[1].trim();

            if (code.equalsIgnoreCase("code") || name.equalsIgnoreCase("name")) {
                continue; // header row
            }
            if (courses.findByOrganisationAndCodeIgnoreCase(organisation, code).isPresent()) {
                skipped.add(code);
                continue;
            }

            Integer expected = null;
            if (cells.length == 3 && !cells[2].isBlank()) {
                try {
                    expected = Integer.parseInt(cells[2].trim());
                } catch (NumberFormatException ignored) {
                    // A bad number is not worth failing an otherwise good row.
                }
            }

            Course course = new Course(organisation, code, name);
            course.setExpectedStudents(expected);
            courses.save(course);
            added++;
        }

        audit.record(organisation, actor, AuditService.CATALOG_CHANGED, added + " courses imported");
        return new ImportResult(added, skipped);
    }

    @Transactional
    public ImportResult importRooms(Organisation organisation, String csv, User actor) {
        List<String> skipped = new ArrayList<>();
        int added = 0;

        for (String line : csv.split("\\r?\\n")) {
            String[] cells = line.split(",", 3);
            if (cells.length < 1 || cells[0].isBlank()) {
                continue;
            }
            String name = cells[0].trim();
            if (name.equalsIgnoreCase("name")) {
                continue;
            }
            if (rooms.findByOrganisationAndNameIgnoreCase(organisation, name).isPresent()) {
                skipped.add(name);
                continue;
            }

            int capacity = 0;
            if (cells.length >= 2 && !cells[1].isBlank()) {
                try {
                    capacity = Integer.parseInt(cells[1].trim());
                } catch (NumberFormatException ignored) {
                    // Treat an unreadable capacity as "not recorded".
                }
            }

            Room room = new Room(organisation, name, capacity);
            if (cells.length == 3 && !cells[2].isBlank()) {
                room.setFacilities(cells[2].trim());
            }
            rooms.save(room);
            added++;
        }

        audit.record(organisation, actor, AuditService.CATALOG_CHANGED, added + " rooms imported");
        return new ImportResult(added, skipped);
    }

    public record ImportResult(int added, List<String> skipped) {

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(added).append(added == 1 ? " row imported" : " rows imported");
            if (!skipped.isEmpty()) {
                sb.append(". Skipped ").append(skipped.size()).append(" already present: ")
                        .append(String.join(", ", skipped.size() > 8 ? skipped.subList(0, 8) : skipped));
                if (skipped.size() > 8) {
                    sb.append(" and ").append(skipped.size() - 8).append(" more");
                }
            }
            return sb.append('.').toString();
        }
    }
}

package com.chalkline.service;

import com.chalkline.domain.*;
import com.chalkline.repo.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Creating an organisation, and changing its settings.
 *
 * Signing up creates the organisation and its first administrator together,
 * in one transaction: an organisation with nobody able to administer it is
 * not a useful thing to leave behind if the second step fails.
 */
@Service
public class OrganisationService {

    private static final int MAX_SLOTS = 24;
    private static final int MAX_DAYS = 7;

    private final OrganisationRepository organisations;
    private final UserRepository users;
    private final CourseRepository courses;
    private final RoomRepository rooms;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public OrganisationService(OrganisationRepository organisations,
                               UserRepository users,
                               CourseRepository courses,
                               RoomRepository rooms,
                               PasswordEncoder passwordEncoder,
                               AuditService audit) {
        this.organisations = organisations;
        this.users = users;
        this.courses = courses;
        this.rooms = rooms;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    /**
     * Public sign-up: a new institution and its first administrator.
     *
     * @return the administrator account that was created
     */
    @Transactional
    public User signUp(String organisationName, String displayName, String email, String password,
                       int minPasswordLength) {

        String cleanOrg = organisationName == null ? "" : organisationName.trim();
        String cleanName = displayName == null ? "" : displayName.trim();
        String cleanEmail = User.normaliseEmail(email);

        if (cleanOrg.isEmpty() || cleanName.isEmpty() || cleanEmail == null || cleanEmail.isEmpty()) {
            throw new ValidationException("Institution, your name and your email are all required.");
        }
        if (!cleanEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}")) {
            throw new ValidationException("That does not look like an email address.");
        }
        if (password == null || password.length() < minPasswordLength) {
            throw new ValidationException("Password must be at least " + minPasswordLength + " characters.");
        }
        if (users.existsByEmail(cleanEmail)) {
            throw new ValidationException(
                    "There is already an account for " + cleanEmail + ". Sign in instead, or use another address.");
        }

        Organisation organisation = new Organisation(
                cleanOrg, Slugs.unique(cleanOrg, organisations::existsBySlug));
        organisations.save(organisation);

        User admin = new User(organisation, cleanEmail, passwordEncoder.encode(password),
                cleanName, Role.ADMIN);
        admin.setCalendarToken(Tokens.calendarToken());
        users.save(admin);

        audit.record(organisation, admin, AuditService.SETTINGS_CHANGED,
                "Organisation created on the " + organisation.getPlan().getLabel() + " plan");
        return admin;
    }

    @Transactional(readOnly = true)
    public Organisation requireById(Long id) {
        return organisations.findById(id)
                .orElseThrow(() -> new ValidationException("That organisation no longer exists."));
    }

    @Transactional
    public void updateProfile(Organisation organisation, String name, String accentColour, User actor) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new ValidationException("The institution name cannot be empty.");
        }
        if (accentColour != null && !accentColour.matches("#[0-9a-fA-F]{6}")) {
            throw new ValidationException("Pick a colour in the form #RRGGBB.");
        }

        organisation.setName(cleanName);
        if (accentColour != null && !accentColour.isBlank()) {
            organisation.setAccentColour(accentColour);
        }
        organisations.save(organisation);
        audit.record(organisation, actor, AuditService.SETTINGS_CHANGED, "Institution profile updated");
    }

    @Transactional
    public void updateScheduling(Organisation organisation, String days, String slots,
                                 boolean preventRoomClashes, boolean allowSelfRegistration, User actor) {

        List<String> dayList = parseList(days, "day");
        List<String> slotList = parseList(slots, "time slot");

        if (dayList.size() > MAX_DAYS) {
            throw new ValidationException("A week cannot have more than " + MAX_DAYS + " days.");
        }
        if (slotList.size() > MAX_SLOTS) {
            throw new ValidationException("A day cannot have more than " + MAX_SLOTS + " time slots.");
        }

        organisation.setScheduleDays(String.join(",", dayList));
        organisation.setScheduleSlots(String.join(",", slotList));
        organisation.setPreventRoomClashes(preventRoomClashes);
        organisation.setAllowSelfRegistration(allowSelfRegistration);
        organisations.save(organisation);

        audit.record(organisation, actor, AuditService.SETTINGS_CHANGED,
                "Timetable shape set to " + dayList.size() + " days by " + slotList.size() + " slots");
    }

    private List<String> parseList(String csv, String what) {
        if (csv == null || csv.isBlank()) {
            throw new ValidationException("Give at least one " + what + ".");
        }
        List<String> parsed = java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (parsed.isEmpty()) {
            throw new ValidationException("Give at least one " + what + ".");
        }
        return parsed;
    }

    /** Sample rooms and courses, so a brand-new account is not an empty grid. */
    @Transactional
    public void seedStarterCatalogue(Organisation organisation) {
        if (courses.countByOrganisation(organisation) > 0 || rooms.countByOrganisation(organisation) > 0) {
            return;
        }
        rooms.saveAll(List.of(
                new Room(organisation, "Lecture Hall A", 120),
                new Room(organisation, "Lecture Hall B", 90),
                new Room(organisation, "Lab 1", 30),
                new Room(organisation, "Seminar Room 3", 24)
        ));
        courses.saveAll(List.of(
                new Course(organisation, "CS101", "Introduction to Programming"),
                new Course(organisation, "CS204", "Data Structures and Algorithms"),
                new Course(organisation, "MTH110", "Discrete Mathematics"),
                new Course(organisation, "ENG101", "Academic Writing")
        ));
    }
}

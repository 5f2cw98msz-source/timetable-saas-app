package com.chalkline.service;

import com.chalkline.config.AppProperties;
import com.chalkline.domain.*;
import com.chalkline.repo.TimetableEntryRepository;
import com.chalkline.repo.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Staff accounts within one organisation.
 *
 * Every lookup that could cross a tenant boundary takes an Organisation and
 * filters on it. That is what stops one institution reaching another's data
 * by guessing an id.
 */
@Service
public class UserService {

    private final UserRepository users;
    private final TimetableEntryRepository entries;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final PlanPolicy planPolicy;
    private final AuditService audit;

    public UserService(UserRepository users,
                       TimetableEntryRepository entries,
                       PasswordEncoder passwordEncoder,
                       AppProperties properties,
                       PlanPolicy planPolicy,
                       AuditService audit) {
        this.users = users;
        this.entries = entries;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.planPolicy = planPolicy;
        this.audit = audit;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public List<User> findMembers(Organisation organisation) {
        return users.findByOrganisationOrderByRoleAscDisplayNameAsc(organisation);
    }

    @Transactional(readOnly = true)
    public List<User> findLecturers(Organisation organisation) {
        return users.findByOrganisationAndRoleOrderByDisplayNameAsc(organisation, Role.LECTURER);
    }

    @Transactional(readOnly = true)
    public User requireByEmail(String email) {
        return users.findByEmail(User.normaliseEmail(email))
                .orElseThrow(() -> new ValidationException("No account for " + email + "."));
    }

    /** Tenant-safe lookup: an id from another organisation is simply not found. */
    @Transactional(readOnly = true)
    public User requireInOrganisation(Long id, Organisation organisation) {
        return users.findByIdAndOrganisation(id, organisation)
                .orElseThrow(() -> new ValidationException("That account is not part of your organisation."));
    }

    @Transactional(readOnly = true)
    public User requireMemberByEmail(String email, Organisation organisation) {
        return users.findByEmailAndOrganisation(User.normaliseEmail(email), organisation)
                .orElseThrow(() -> new ValidationException(
                        "No account for " + email + " in your organisation."));
    }

    @Transactional(readOnly = true)
    public long countClassesFor(User user) {
        return entries.countByLecturer(user);
    }

    // ---- writes ----

    /**
     * Self-service signup into an EXISTING organisation. Always creates a
     * lecturer, never an administrator, exactly as the desktop app did.
     */
    @Transactional
    public User registerLecturer(Organisation organisation, String email, String displayName, String password) {
        if (!organisation.isAllowSelfRegistration()) {
            throw new ValidationException(
                    "This institution creates accounts centrally. Ask an administrator to add you.");
        }
        return create(organisation, email, displayName, password, Role.LECTURER, false);
    }

    /** Added by an administrator. They must choose their own password at first sign-in. */
    @Transactional
    public User addMember(Organisation organisation, String email, String displayName,
                          String password, Role role, User actor) {

        User created = create(organisation, email, displayName, password, role, true);
        audit.record(organisation, actor, AuditService.MEMBER_ADDED,
                created.getDisplayName() + " (" + created.getEmail() + ") added as " + role.getLabel());
        return created;
    }

    private User create(Organisation organisation, String email, String displayName,
                        String password, Role role, boolean mustChangePassword) {

        String cleanEmail = User.normaliseEmail(email);
        String cleanName = displayName == null ? "" : displayName.trim();

        if (cleanEmail == null || cleanEmail.isEmpty() || cleanName.isEmpty()) {
            throw new ValidationException("Full name and email address are both required.");
        }
        if (!cleanEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}")) {
            throw new ValidationException("That does not look like an email address.");
        }
        requireStrongEnough(password);

        // Checked before the insert so the message is helpful, and again by a
        // unique constraint so two simultaneous signups cannot both succeed.
        if (users.existsByEmail(cleanEmail)) {
            throw new ValidationException("There is already an account for " + cleanEmail + ".");
        }
        planPolicy.requireStaffHeadroom(organisation);

        User user = new User(organisation, cleanEmail, passwordEncoder.encode(password), cleanName, role);
        user.setMustChangePassword(mustChangePassword);
        user.setCalendarToken(Tokens.calendarToken());

        try {
            return users.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new ValidationException("There is already an account for " + cleanEmail + ".");
        }
    }

    /**
     * Removing an account also removes the classes it owns, because a
     * scheduled class cannot point at a lecturer who no longer exists. The
     * caller is expected to have shown the count first.
     */
    @Transactional
    public void delete(Long id, Organisation organisation, User actingUser) {
        User target = requireInOrganisation(id, organisation);

        if (target.getId().equals(actingUser.getId())) {
            throw new ValidationException("You cannot delete the account you are signed in with.");
        }
        if (target.getRole() == Role.ADMIN
                && users.countByOrganisationAndRole(organisation, Role.ADMIN) <= 1) {
            throw new ValidationException(
                    "This is the only administrator. Make somebody else an administrator first.");
        }

        entries.deleteByLecturer(target);
        users.delete(target);
        audit.record(organisation, actingUser, AuditService.MEMBER_REMOVED,
                target.getDisplayName() + " (" + target.getEmail() + ") removed");
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, Organisation organisation, User actingUser) {
        User target = requireInOrganisation(id, organisation);

        if (target.getId().equals(actingUser.getId())) {
            throw new ValidationException("You cannot suspend the account you are signed in with.");
        }
        if (!enabled && target.getRole() == Role.ADMIN
                && users.countByOrganisationAndRole(organisation, Role.ADMIN) <= 1) {
            throw new ValidationException("This is the only administrator. Appoint another one first.");
        }
        if (enabled) {
            planPolicy.requireStaffHeadroom(organisation);
        }

        target.setEnabled(enabled);
        users.save(target);
        audit.record(organisation, actingUser,
                enabled ? AuditService.MEMBER_RESTORED : AuditService.MEMBER_SUSPENDED,
                target.getDisplayName());
    }

    @Transactional
    public void changeRole(Long id, Role role, Organisation organisation, User actingUser) {
        User target = requireInOrganisation(id, organisation);

        if (target.getId().equals(actingUser.getId())) {
            throw new ValidationException("You cannot change your own role.");
        }
        if (role != Role.ADMIN && target.getRole() == Role.ADMIN
                && users.countByOrganisationAndRole(organisation, Role.ADMIN) <= 1) {
            throw new ValidationException("This is the only administrator. Appoint another one first.");
        }

        target.setRole(role);
        users.save(target);
        audit.record(organisation, actingUser, AuditService.SETTINGS_CHANGED,
                target.getDisplayName() + " is now " + role.getLabel());
    }

    @Transactional
    public void updateProfile(User user, String displayName, boolean notifyOnChange) {
        String cleanName = displayName == null ? "" : displayName.trim();
        if (cleanName.isEmpty()) {
            throw new ValidationException("Your name cannot be empty.");
        }
        user.setDisplayName(cleanName);
        user.setNotifyOnChange(notifyOnChange);
        users.save(user);
    }

    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword, String confirm) {
        User user = requireByEmail(email);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ValidationException("Your current password is not correct.");
        }
        requireStrongEnough(newPassword);
        if (!newPassword.equals(confirm)) {
            throw new ValidationException("The two new passwords do not match.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ValidationException("Choose a password you have not used here before.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.save(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword, Organisation organisation, User actingUser) {
        User target = requireInOrganisation(id, organisation);
        requireStrongEnough(newPassword);

        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setMustChangePassword(true);
        users.save(target);
        audit.record(organisation, actingUser, AuditService.SETTINGS_CHANGED,
                "Password reset for " + target.getDisplayName());
    }

    /** New random value for the personal calendar URL, revoking the old one. */
    @Transactional
    public String regenerateCalendarToken(User user) {
        user.setCalendarToken(Tokens.calendarToken());
        users.save(user);
        return user.getCalendarToken();
    }

    @Transactional
    public void recordSignIn(User user) {
        user.setLastLoginAt(Instant.now());
        users.save(user);
    }

    private void requireStrongEnough(String password) {
        int min = properties.security().minPasswordLength();
        if (password == null || password.length() < min) {
            throw new ValidationException("Password must be at least " + min + " characters.");
        }
    }
}

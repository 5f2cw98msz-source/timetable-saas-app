package com.chalkline.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/**
 * A staff account, belonging to exactly one organisation.
 *
 * Login is by EMAIL, not by the username the desktop app used. Once more than
 * one institution shares the system, two of them will each want an account
 * called "admin"; an email address is the only identifier that stays unique
 * across all of them, and it is also what password resets and invitations
 * need.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
        indexes = @Index(name = "ix_users_org", columnList = "organisation_id")
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_user_org"))
    private Organisation organisation;

    /** Always stored lower-case, so sign-in is not case-sensitive. */
    @Column(nullable = false, length = 190)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /** Opt-in to emails about timetable changes. Off by default. */
    @Column(name = "notify_on_change", nullable = false)
    private boolean notifyOnChange = false;

    /**
     * Secret in this lecturer's personal calendar subscription URL. Random,
     * and regenerable, so a link that has been shared too widely can be
     * revoked without changing anything else about the account.
     */
    @Column(name = "calendar_token", length = 64)
    private String calendarToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
        // required by JPA
    }

    public User(Organisation organisation, String email, String passwordHash, String displayName, Role role) {
        this.organisation = organisation;
        this.email = normaliseEmail(email);
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    public Long getId() {
        return id;
    }

    public Organisation getOrganisation() {
        return organisation;
    }

    public void setOrganisation(Organisation organisation) {
        this.organisation = organisation;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normaliseEmail(email);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public boolean isNotifyOnChange() {
        return notifyOnChange;
    }

    public void setNotifyOnChange(boolean notifyOnChange) {
        this.notifyOnChange = notifyOnChange;
    }

    public String getCalendarToken() {
        return calendarToken;
    }

    public void setCalendarToken(String calendarToken) {
        this.calendarToken = calendarToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** First name plus last initial, for compact places like a grid cell. */
    public String getShortName() {
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length < 2) {
            return displayName;
        }
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    public String getInitials() {
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return displayName + " <" + email + ">";
    }
}

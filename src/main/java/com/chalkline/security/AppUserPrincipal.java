package com.chalkline.security;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.Plan;
import com.chalkline.domain.Role;
import com.chalkline.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The signed-in user, as Spring Security sees them.
 *
 * Carries the organisation and plan as well, so the page chrome can show them
 * without another database hit on every request. Authorisation still re-reads
 * the user from the database -- this is a snapshot taken at sign-in, and a
 * snapshot is not a source of truth.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String displayName;
    private final String initials;
    private final Role role;
    private final boolean enabled;
    private final boolean mustChangePassword;

    private final Long organisationId;
    private final String organisationName;
    private final String organisationSlug;
    private final String accentColour;
    private final Plan plan;

    public AppUserPrincipal(User user) {
        Organisation organisation = user.getOrganisation();

        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.displayName = user.getDisplayName();
        this.initials = user.getInitials();
        this.role = user.getRole();
        this.enabled = user.isEnabled();
        this.mustChangePassword = user.isMustChangePassword();

        this.organisationId = organisation.getId();
        this.organisationName = organisation.getName();
        this.organisationSlug = organisation.getSlug();
        this.accentColour = organisation.getAccentColour();
        this.plan = organisation.getEffectivePlan();
    }

    public Long getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getInitials() { return initials; }
    public Role getRole() { return role; }
    public boolean isAdmin() { return role == Role.ADMIN; }
    public boolean isMustChangePassword() { return mustChangePassword; }

    public Long getOrganisationId() { return organisationId; }
    public String getOrganisationName() { return organisationName; }
    public String getOrganisationSlug() { return organisationSlug; }
    public String getAccentColour() { return accentColour; }
    public Plan getPlan() { return plan; }
    public boolean isPremium() { return plan == Plan.PREMIUM; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Spring Security's "username" is the email address here. */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

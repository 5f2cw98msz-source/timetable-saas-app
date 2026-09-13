package com.chalkline.web;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.User;
import com.chalkline.security.AppUserPrincipal;
import com.chalkline.service.UserService;
import org.springframework.stereotype.Component;

/**
 * Turns the signed-in principal into the live database record.
 *
 * Controllers use this rather than trusting the principal's own copy of the
 * role, plan or organisation: the principal was built at sign-in and can be
 * hours stale, whereas permissions have to be decided on what is true now.
 */
@Component
public class CurrentUser {

    private final UserService userService;

    public CurrentUser(UserService userService) {
        this.userService = userService;
    }

    public User require(AppUserPrincipal principal) {
        return userService.requireByEmail(principal.getUsername());
    }

    public Organisation organisationOf(AppUserPrincipal principal) {
        return require(principal).getOrganisation();
    }
}

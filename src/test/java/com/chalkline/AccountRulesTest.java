package com.chalkline;

import com.chalkline.domain.Role;
import com.chalkline.domain.User;
import com.chalkline.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sign-up, passwords and the guards on removing people. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:accounts;DB_CLOSE_DELAY=-1",
        "app.demo.email="
})
@Import(TestFixtures.class)
@Transactional
class AccountRulesTest {

    @Autowired TestFixtures fixtures;
    @Autowired UserService userService;
    @Autowired OrganisationService organisationService;
    @Autowired PasswordEncoder passwordEncoder;

    TestFixtures.Fixture uni;

    @BeforeEach
    void setUp() {
        uni = fixtures.institution("Account University", "acct.edu");
    }

    @Test
    @DisplayName("signing up creates the institution and its first administrator together")
    void signUpCreatesBoth() {
        User admin = organisationService.signUp("New College", "Head of School",
                "head@new.edu", "password123", 8);

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getOrganisation().getName()).isEqualTo("New College");
        assertThat(admin.getOrganisation().getSlug()).isEqualTo("new-college");
        assertThat(admin.getCalendarToken()).isNotBlank();
    }

    @Test
    @DisplayName("two institutions with the same name get different slugs")
    void slugsAreMadeUnique() {
        organisationService.signUp("Same Name", "A", "a@one.edu", "password123", 8);
        User second = organisationService.signUp("Same Name", "B", "b@two.edu", "password123", 8);

        assertThat(second.getOrganisation().getSlug()).isEqualTo("same-name-2");
    }

    @Test
    @DisplayName("passwords are stored as bcrypt hashes, never in plain text")
    void passwordsAreHashed() {
        assertThat(uni.ada().getPasswordHash())
                .startsWith("{bcrypt}")
                .doesNotContain("password123");
        assertThat(passwordEncoder.matches("password123", uni.ada().getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("an email address can only be used once across the whole system")
    void emailsAreGloballyUnique() {
        assertThatThrownBy(() -> organisationService.signUp("Another College", "Someone",
                "ADA@acct.edu", "password123", 8))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already an account");
    }

    @Test
    @DisplayName("sign-in is not case sensitive")
    void emailLookupIgnoresCase() {
        assertThat(userService.requireByEmail("ADA@ACCT.EDU").getDisplayName())
                .isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("malformed emails and short passwords are refused")
    void inputIsValidated() {
        assertThatThrownBy(() -> organisationService.signUp("X", "Y", "not-an-email", "password123", 8))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("email address");

        assertThatThrownBy(() -> organisationService.signUp("X", "Y", "ok@x.edu", "short", 8))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least 8");
    }

    @Test
    @DisplayName("self-registration can be switched off per institution")
    void selfRegistrationIsOptional() {
        uni.organisation().setAllowSelfRegistration(false);

        assertThatThrownBy(() -> userService.registerLecturer(uni.organisation(),
                "new@acct.edu", "New Person", "password123"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("centrally");
    }

    @Test
    @DisplayName("self-registration always creates a lecturer, never an administrator")
    void selfRegistrationCannotCreateAnAdmin() {
        User joined = userService.registerLecturer(uni.organisation(),
                "new@acct.edu", "New Person", "password123");
        assertThat(joined.getRole()).isEqualTo(Role.LECTURER);
    }

    @Test
    @DisplayName("an admin-created account must choose its own password first")
    void adminCreatedAccountsMustChangePassword() {
        User created = userService.addMember(uni.organisation(), "temp@acct.edu",
                "Temp Person", "temporary123", Role.LECTURER, uni.admin());
        assertThat(created.isMustChangePassword()).isTrue();

        userService.changePassword("temp@acct.edu", "temporary123", "chosenbyme123", "chosenbyme123");
        assertThat(userService.requireByEmail("temp@acct.edu").isMustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("changing a password needs the current one, and the new ones must match")
    void passwordChangeIsGuarded() {
        assertThatThrownBy(() ->
                userService.changePassword("ada@acct.edu", "wrong", "newpassword1", "newpassword1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("current password is not correct");

        assertThatThrownBy(() ->
                userService.changePassword("ada@acct.edu", "password123", "newpassword1", "different123"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("do not match");

        assertThatThrownBy(() ->
                userService.changePassword("ada@acct.edu", "password123", "password123", "password123"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not used here before");
    }

    @Test
    @DisplayName("you cannot delete or suspend the account you are signed in with")
    void cannotRemoveYourself() {
        assertThatThrownBy(() ->
                userService.delete(uni.admin().getId(), uni.organisation(), uni.admin()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("signed in with");

        assertThatThrownBy(() ->
                userService.setEnabled(uni.admin().getId(), false, uni.organisation(), uni.admin()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an institution is never left without an administrator")
    void lastAdministratorIsProtected() {
        User second = userService.addMember(uni.organisation(), "boss@acct.edu",
                "Second Admin", "password123", Role.ADMIN, uni.admin());

        // With two admins, removing one is fine.
        userService.delete(uni.admin().getId(), uni.organisation(), second);

        // The survivor cannot demote or remove themselves.
        assertThatThrownBy(() ->
                userService.changeRole(second.getId(), Role.LECTURER, uni.organisation(), second))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("your own role");
    }

    @Test
    @DisplayName("resetting the calendar token invalidates the old link")
    void calendarTokenCanBeRotated() {
        String before = uni.ada().getCalendarToken();
        String after = userService.regenerateCalendarToken(uni.ada());

        assertThat(after).isNotBlank().isNotEqualTo(before);
    }
}

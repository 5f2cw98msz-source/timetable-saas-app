package com.chalkline;

import com.chalkline.config.DataSeeder;
import com.chalkline.domain.Organisation;
import com.chalkline.domain.Role;
import com.chalkline.domain.User;
import com.chalkline.repo.CourseRepository;
import com.chalkline.repo.OrganisationRepository;
import com.chalkline.repo.RoomRepository;
import com.chalkline.repo.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * First-run setup, as the Windows deployer uses it: one institution, created
 * at first start, for a deployment with no public sign-up.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:setupboot;DB_CLOSE_DELAY=-1",
        "app.setup.organisation-name=Abetifi Technical University",
        "app.setup.admin-name=Kwame Asante",
        "app.setup.admin-email=IT@Northfield.example",
        "app.setup.admin-password=typed-into-the-deployer",
        "app.setup.plan=PREMIUM",
        "app.demo.email="
})
@ExtendWith(OutputCaptureExtension.class)
class SetupBootstrapTest {

    @Autowired OrganisationRepository organisations;
    @Autowired UserRepository users;
    @Autowired CourseRepository courses;
    @Autowired RoomRepository rooms;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSeeder seeder;

    @Test
    @DisplayName("first start creates the institution and its administrator")
    void createsInstitutionAndAdmin() {
        assertThat(organisations.count()).isEqualTo(1);
        Organisation org = organisations.findAll().get(0);
        assertThat(org.getName()).isEqualTo("Abetifi Technical University");

        User admin = users.findByEmail("it@northfield.example").orElseThrow();
        assertThat(admin.getDisplayName()).isEqualTo("Kwame Asante");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches("typed-into-the-deployer", admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("the administrator must choose their own password at first sign-in")
    void adminMustChangePassword() {
        // The password was typed into a deployment tool, so whoever ran it has seen it.
        assertThat(users.findByEmail("it@northfield.example").orElseThrow().isMustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("a real deployment starts with no sample courses or rooms")
    void noSampleDataByDefault() {
        Organisation org = organisations.findAll().get(0);
        assertThat(courses.countByOrganisation(org)).isZero();
        assertThat(rooms.countByOrganisation(org)).isZero();
    }

    @Test
    @DisplayName("a self-hosted institution gets every feature and no staff cap, with no billing behind it")
    void selfHostedIsPremium() {
        Organisation org = organisations.findAll().get(0);
        assertThat(org.getEffectivePlan()).isEqualTo(com.chalkline.domain.Plan.PREMIUM);
        assertThat(org.getEffectivePlan().isUnlimitedStaff()).isTrue();
        for (com.chalkline.domain.Feature f : com.chalkline.domain.Feature.values()) {
            assertThat(org.hasFeature(f)).as(f.name()).isTrue();
        }
        assertThat(org.getStripeSubscriptionId()).isNull();
    }

    @Test
    @DisplayName("running again on a database in use changes nothing, and says so")
    void neverTouchesAnExistingDatabase(CapturedOutput output) throws Exception {
        long orgsBefore = organisations.count();
        long usersBefore = users.count();

        seeder.run(new DefaultApplicationArguments());

        assertThat(organisations.count()).isEqualTo(orgsBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
        // The deployer reads this line to know setup needs nothing more.
        assertThat(output).contains("Initial setup: an institution already exists, so nothing was created.");
    }
}

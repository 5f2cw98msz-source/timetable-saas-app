package com.chalkline;

import com.chalkline.domain.Organisation;
import com.chalkline.repo.CourseRepository;
import com.chalkline.repo.OrganisationRepository;
import com.chalkline.repo.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Sample courses only when explicitly asked for, and too short a password creates nothing. */
class SetupWithSamplesTest {

    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:h2:mem:setupsamples;DB_CLOSE_DELAY=-1",
            "app.setup.organisation-name=Sample College",
            "app.setup.admin-email=admin@sample.example",
            "app.setup.admin-password=long-enough-123",
            "app.setup.seed-sample-catalogue=true",
            "app.demo.email="
    })
    @org.junit.jupiter.api.Nested
    class WhenAsked {
        @Autowired OrganisationRepository organisations;
        @Autowired CourseRepository courses;

        @Test
        @DisplayName("sample courses are added when the deployer asks for them")
        void seedsSamples() {
            Organisation org = organisations.findAll().get(0);
            assertThat(courses.countByOrganisation(org)).isPositive();
        }
    }

    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:h2:mem:setupshort;DB_CLOSE_DELAY=-1",
            "app.setup.organisation-name=Short Password College",
            "app.setup.admin-email=admin@short.example",
            "app.setup.admin-password=short",
            "app.demo.email="
    })
    @org.junit.jupiter.api.Nested
    class WhenPasswordTooShort {
        @Autowired OrganisationRepository organisations;
        @Autowired UserRepository users;

        @Test
        @DisplayName("too short a password creates nothing rather than a weak administrator")
        void refusesWeakPassword() {
            assertThat(organisations.count()).isZero();
            assertThat(users.findByEmail("admin@short.example")).isEmpty();
        }
    }
}

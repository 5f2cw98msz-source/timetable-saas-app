package com.chalkline.config;

import com.chalkline.domain.*;
import com.chalkline.repo.*;
import com.chalkline.service.OrganisationService;
import com.chalkline.service.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional demo data for a fresh install.
 *
 * There is no "first administrator" any more: this is a product people sign up
 * to, and the first account of each institution is created by whoever signs it
 * up. This runner exists only so a local copy or a demo deployment can start
 * with something on screen.
 *
 * Switched on with DEMO_ORG_EMAIL. It does nothing at all otherwise, and never
 * touches a database that already has organisations in it.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final OrganisationRepository organisations;
    private final UserRepository users;
    private final OrganisationService organisationService;
    private final PasswordEncoder passwordEncoder;
    private final String demoEmail;
    private final String demoPassword;
    private final String demoOrgName;

    public DataSeeder(OrganisationRepository organisations,
                      UserRepository users,
                      OrganisationService organisationService,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.demo.email:}") String demoEmail,
                      @Value("${app.demo.password:}") String demoPassword,
                      @Value("${app.demo.organisation-name:Demo University}") String demoOrgName) {
        this.organisations = organisations;
        this.users = users;
        this.organisationService = organisationService;
        this.passwordEncoder = passwordEncoder;
        this.demoEmail = demoEmail;
        this.demoPassword = demoPassword;
        this.demoOrgName = demoOrgName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (demoEmail == null || demoEmail.isBlank()) {
            return;
        }
        if (organisations.count() > 0) {
            return; // Never modify a database that is already in use.
        }
        if (demoPassword == null || demoPassword.length() < 8) {
            log.warn("DEMO_ORG_EMAIL is set but DEMO_ORG_PASSWORD is missing or shorter than 8 characters. "
                    + "No demo organisation created.");
            return;
        }

        Organisation organisation = organisations.save(
                new Organisation(demoOrgName, com.chalkline.service.Slugs.from(demoOrgName)));

        User admin = new User(organisation, demoEmail, passwordEncoder.encode(demoPassword),
                "Demo Administrator", Role.ADMIN);
        admin.setCalendarToken(Tokens.calendarToken());
        users.save(admin);

        organisationService.seedStarterCatalogue(organisation);

        log.info("Demo organisation '{}' created with administrator {}", demoOrgName, demoEmail);
    }
}

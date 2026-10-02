package com.chalkline.config;

import com.chalkline.domain.*;
import com.chalkline.repo.*;
import com.chalkline.service.OrganisationService;
import com.chalkline.service.Slugs;
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
 * Creates an institution on first start, for deployments that serve one
 * institution and so have no public sign-up.
 *
 * Two modes, and they are deliberately different:
 *
 *   SETUP (app.setup.*, INITIAL_* variables) is for a real deployment, such as
 *   a university hosting this on its own network. The administrator is made to
 *   choose their own password at first sign-in, because the one typed into a
 *   deployment tool has been seen by whoever ran it. No sample courses are
 *   added unless asked for, because fake data on a real system is something a
 *   school then has to find and delete.
 *
 *   DEMO (app.demo.*, DEMO_ORG_* variables) is for a laptop or a sales demo.
 *   Sample rooms and courses, and no forced password change.
 *
 * Either way it runs only when the database has no organisations at all, so it
 * can never alter a system that is already in use. SETUP wins if both are set.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final OrganisationRepository organisations;
    private final UserRepository users;
    private final OrganisationService organisationService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    private final String setupOrgName;
    private final String setupAdminName;
    private final String setupEmail;
    private final String setupPassword;
    private final boolean setupSeedSamples;
    private final Plan setupPlan;

    private final String demoEmail;
    private final String demoPassword;
    private final String demoOrgName;

    public DataSeeder(OrganisationRepository organisations,
                      UserRepository users,
                      OrganisationService organisationService,
                      PasswordEncoder passwordEncoder,
                      AppProperties properties,
                      @Value("${app.setup.organisation-name:}") String setupOrgName,
                      @Value("${app.setup.admin-name:Administrator}") String setupAdminName,
                      @Value("${app.setup.admin-email:}") String setupEmail,
                      @Value("${app.setup.admin-password:}") String setupPassword,
                      @Value("${app.setup.seed-sample-catalogue:false}") boolean setupSeedSamples,
                      @Value("${app.setup.plan:FREE}") Plan setupPlan,
                      @Value("${app.demo.email:}") String demoEmail,
                      @Value("${app.demo.password:}") String demoPassword,
                      @Value("${app.demo.organisation-name:Demo University}") String demoOrgName) {
        this.organisations = organisations;
        this.users = users;
        this.organisationService = organisationService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.setupOrgName = setupOrgName;
        this.setupAdminName = setupAdminName;
        this.setupEmail = setupEmail;
        this.setupPassword = setupPassword;
        this.setupSeedSamples = setupSeedSamples;
        this.setupPlan = setupPlan;
        this.demoEmail = demoEmail;
        this.demoPassword = demoPassword;
        this.demoOrgName = demoOrgName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean setup = isSet(setupEmail);
        boolean demo = isSet(demoEmail);
        if (!setup && !demo) {
            return;
        }
        if (organisations.count() > 0) {
            if (setup) {
                // Said out loud so the Windows deployer can tell this apart from
                // a setup that never ran: the institution is there already.
                log.info("Initial setup: an institution already exists, so nothing was created.");
            }
            return; // Never modify a database that is already in use.
        }

        if (setup) {
            bootstrap("Initial setup", setupOrgName, setupAdminName, setupEmail, setupPassword,
                    true, setupSeedSamples, setupPlan);
        } else {
            bootstrap("Demo", demoOrgName, "Demo Administrator", demoEmail, demoPassword,
                    false, properties.demo().seedStarterCatalogue(), Plan.FREE);
        }
    }

    private void bootstrap(String mode, String orgName, String adminName, String email, String password,
                           boolean mustChangePassword, boolean seedSamples, Plan plan) {

        int minLength = properties.security().minPasswordLength();
        if (!isSet(orgName)) {
            log.warn("{}: an administrator email is set but no institution name. Nothing created.", mode);
            return;
        }
        if (password == null || password.length() < minLength) {
            log.warn("{}: the administrator password is missing or shorter than {} characters. Nothing created.",
                    mode, minLength);
            return;
        }

        Organisation organisation = new Organisation(orgName.trim(), Slugs.from(orgName));

        // A school hosting its own copy gets every feature. Without this it
        // would land on the free plan and its five-account cap, which no real
        // department fits inside. There is no subscription behind it and no
        // billing involved: the organisation simply is Premium.
        if (plan == Plan.PREMIUM) {
            organisation.setPlan(Plan.PREMIUM);
            organisation.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        }
        organisations.save(organisation);

        User admin = new User(organisation, email, passwordEncoder.encode(password),
                isSet(adminName) ? adminName.trim() : "Administrator", Role.ADMIN);
        admin.setCalendarToken(Tokens.calendarToken());
        admin.setMustChangePassword(mustChangePassword);
        users.save(admin);

        if (seedSamples) {
            organisationService.seedStarterCatalogue(organisation);
        }

        log.info("{}: institution '{}' created with administrator {} on the {} plan{}", mode,
                organisation.getName(), email, organisation.getEffectivePlan().getLabel(),
                mustChangePassword ? " (must choose a new password at first sign-in)" : "");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}

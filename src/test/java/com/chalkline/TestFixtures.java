package com.chalkline;

import com.chalkline.domain.*;
import com.chalkline.repo.OrganisationRepository;
import com.chalkline.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Builds throwaway institutions for tests to work against. */
@Component
public class TestFixtures {

    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationService organisationService;
    @Autowired UserService userService;
    @Autowired CatalogService catalogService;

    /** An institution with an administrator, two lecturers, two courses and two rooms. */
    public Fixture institution(String name, String emailDomain) {
        User admin = organisationService.signUp(
                name, "The Administrator", "admin@" + emailDomain, "password123", 8);
        Organisation organisation = admin.getOrganisation();

        User ada = userService.addMember(organisation, "ada@" + emailDomain,
                "Ada Lovelace", "password123", Role.LECTURER, admin);
        User alan = userService.addMember(organisation, "alan@" + emailDomain,
                "Alan Turing", "password123", Role.LECTURER, admin);

        // Not what these tests are about, so clear the first-login requirement.
        ada.setMustChangePassword(false);
        alan.setMustChangePassword(false);

        Course maths = catalogService.addCourse(organisation, "MTH110", "Discrete Mathematics", 40, admin);
        Course physics = catalogService.addCourse(organisation, "PHY101", "Mechanics", 120, admin);
        Room lab1 = catalogService.addRoom(organisation, "Lab 1", 30, null, admin);
        Room lab2 = catalogService.addRoom(organisation, "Lab 2", 30, null, admin);

        return new Fixture(organisation, admin, ada, alan, maths, physics, lab1, lab2);
    }

    /** Puts an organisation on the paid plan without going near Stripe. */
    public void makePremium(Organisation organisation) {
        organisation.setPlan(Plan.PREMIUM);
        organisation.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        organisations.save(organisation);
    }

    public record Fixture(
            Organisation organisation,
            User admin,
            User ada,
            User alan,
            Course maths,
            Course physics,
            Room lab1,
            Room lab2
    ) {}
}

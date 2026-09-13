package com.chalkline.service;

import com.chalkline.domain.Feature;
import com.chalkline.domain.Organisation;
import com.chalkline.domain.Plan;
import com.chalkline.domain.Role;
import com.chalkline.repo.UserRepository;
import org.springframework.stereotype.Service;

/**
 * The single place that decides what a plan may do.
 *
 * Every paid feature asks here. Templates hide the controls as a courtesy,
 * but hiding a control is not a limit -- the check that counts is this one,
 * on the server, on the request that would do the work.
 */
@Service
public class PlanPolicy {

    private final UserRepository users;

    public PlanPolicy(UserRepository users) {
        this.users = users;
    }

    public boolean has(Organisation organisation, Feature feature) {
        return organisation.hasFeature(feature);
    }

    /** Throws with an upgrade message unless the organisation has the feature. */
    public void require(Organisation organisation, Feature feature) {
        if (!has(organisation, feature)) {
            throw new UpgradeRequiredException(feature);
        }
    }

    /** True when another staff account would exceed the plan's cap. */
    public boolean staffLimitReached(Organisation organisation) {
        Plan plan = organisation.getEffectivePlan();
        if (plan.isUnlimitedStaff()) {
            return false;
        }
        return users.countByOrganisation(organisation) >= plan.getStaffLimit();
    }

    public void requireStaffHeadroom(Organisation organisation) {
        if (staffLimitReached(organisation)) {
            Plan plan = organisation.getEffectivePlan();
            throw new UpgradeRequiredException(
                    "The " + plan.getLabel() + " plan covers up to " + plan.getStaffLimit()
                            + " staff accounts. Upgrade to add more.");
        }
    }

    public long staffUsed(Organisation organisation) {
        return users.countByOrganisation(organisation);
    }

    public long lecturersUsed(Organisation organisation) {
        return users.countByOrganisationAndRole(organisation, Role.LECTURER);
    }
}

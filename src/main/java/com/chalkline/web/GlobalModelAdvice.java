package com.chalkline.web;

import com.chalkline.config.AppProperties;
import com.chalkline.domain.Plan;
import com.chalkline.domain.User;
import com.chalkline.repo.UserRepository;
import com.chalkline.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Values every page needs: who is signed in, and how the product is branded. */
@ControllerAdvice
public class GlobalModelAdvice {

    private final AppProperties properties;
    private final UserRepository users;

    public GlobalModelAdvice(AppProperties properties, UserRepository users) {
        this.properties = properties;
        this.users = users;
    }

    @ModelAttribute("productName")
    public String productName() {
        return properties.productName();
    }

    @ModelAttribute("supportEmail")
    public String supportEmail() {
        return properties.supportEmail();
    }

    @ModelAttribute("allowSignUp")
    public boolean allowSignUp() {
        return properties.security().allowSignUp();
    }

    @ModelAttribute("minPasswordLength")
    public int minPasswordLength() {
        return properties.security().minPasswordLength();
    }

    @ModelAttribute("premiumPlan")
    public Plan premiumPlan() {
        return Plan.PREMIUM;
    }

    @ModelAttribute("me")
    public AppUserPrincipal me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return principal;
    }

    /**
     * The plan as it is RIGHT NOW, rather than as it was when this person
     * signed in.
     *
     * AppUserPrincipal is a snapshot taken at sign-in, so it goes stale the
     * moment a subscription changes -- and the two moments that matters most
     * are immediately after someone pays, and immediately after a payment
     * fails. Neither is a good time to be showing them the wrong plan.
     *
     * Costs one indexed read on authenticated pages, and nothing at all on
     * the marketing site.
     */
    @ModelAttribute("currentPlan")
    @Transactional(readOnly = true)
    public Plan currentPlan(@AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal == null) {
            return null;
        }
        return users.findByEmail(principal.getUsername())
                .map(User::getOrganisation)
                .map(org -> org.getEffectivePlan())
                .orElse(principal.getPlan());
    }
}

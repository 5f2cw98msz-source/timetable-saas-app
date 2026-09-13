package com.chalkline.web;

import com.chalkline.config.AppProperties;
import com.chalkline.domain.Plan;
import com.chalkline.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Values every page needs: who is signed in, and how the product is branded. */
@ControllerAdvice
public class GlobalModelAdvice {

    private final AppProperties properties;

    public GlobalModelAdvice(AppProperties properties) {
        this.properties = properties;
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
}

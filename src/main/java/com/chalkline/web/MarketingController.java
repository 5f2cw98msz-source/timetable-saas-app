package com.chalkline.web;

import com.chalkline.domain.Feature;
import com.chalkline.domain.Plan;
import com.chalkline.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The public site: what the product is, what it costs, and how to start.
 *
 * Signed-in users skip it and go straight to their timetable -- somebody who
 * already pays for the tool does not need to be sold it again.
 */
@Controller
public class MarketingController {

    @GetMapping("/")
    public String landing(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        if (principal != null) {
            return "redirect:/timetable";
        }
        model.addAttribute("plans", Plan.values());
        model.addAttribute("premiumFeatures", Feature.values());
        return "marketing/landing";
    }

    @GetMapping("/pricing")
    public String pricing(Model model) {
        model.addAttribute("plans", Plan.values());
        model.addAttribute("premiumFeatures", Feature.values());
        return "marketing/pricing";
    }
}

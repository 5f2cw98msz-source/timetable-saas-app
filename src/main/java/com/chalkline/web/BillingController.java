package com.chalkline.web;

import com.chalkline.domain.Feature;
import com.chalkline.domain.Organisation;
import com.chalkline.domain.Plan;
import com.chalkline.domain.User;
import com.chalkline.security.AppUserPrincipal;
import com.chalkline.service.BillingService;
import com.chalkline.service.PlanPolicy;
import com.chalkline.service.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Subscriptions: viewing the plan, upgrading, and receiving Stripe webhooks. */
@Controller
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final BillingService billingService;
    private final PlanPolicy planPolicy;
    private final CurrentUser currentUser;

    public BillingController(BillingService billingService, PlanPolicy planPolicy, CurrentUser currentUser) {
        this.billingService = billingService;
        this.planPolicy = planPolicy;
        this.currentUser = currentUser;
    }

    @GetMapping("/settings/billing")
    public String billing(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Organisation organisation = currentUser.organisationOf(principal);

        model.addAttribute("organisation", organisation);
        model.addAttribute("plans", Plan.values());
        model.addAttribute("features", Feature.values());
        model.addAttribute("effectivePlan", organisation.getEffectivePlan());
        model.addAttribute("billingConfigured", billingService.isConfigured());
        model.addAttribute("manualUpgradeAvailable", billingService.isManualUpgradeAvailable());
        model.addAttribute("staffUsed", planPolicy.staffUsed(organisation));
        return "settings/billing";
    }

    @PostMapping("/settings/billing/checkout")
    public String checkout(@AuthenticationPrincipal AppUserPrincipal principal) {
        User actor = currentUser.require(principal);
        // Straight to Stripe's own hosted page: no card details ever reach us.
        return "redirect:" + billingService.createCheckoutUrl(actor.getOrganisation(), actor);
    }

    @PostMapping("/settings/billing/portal")
    public String portal(@AuthenticationPrincipal AppUserPrincipal principal) {
        return "redirect:" + billingService.createPortalUrl(currentUser.organisationOf(principal));
    }

    /** Development and demo only. Refused whenever real payments are configured. */
    @PostMapping("/settings/billing/manual")
    public String manual(@AuthenticationPrincipal AppUserPrincipal principal,
                         @RequestParam boolean premium,
                         RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        billingService.manualUpgrade(actor.getOrganisation(), actor, premium);

        redirectAttributes.addFlashAttribute("successMessage", premium
                ? "Switched to Premium. Payments are not configured on this deployment, so nothing was charged."
                : "Switched back to Free.");
        return "redirect:/settings/billing";
    }

    /**
     * Stripe posts here.
     *
     * Exempt from CSRF and from sign-in, because Stripe has neither a session
     * nor a token. It is authenticated by the signature on the request, which
     * BillingService verifies before acting on anything.
     */
    @PostMapping("/billing/webhook")
    @ResponseBody
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false)
                                          String signature) {
        try {
            billingService.handleWebhook(payload, signature);
            return ResponseEntity.ok("ok");

        } catch (ValidationException e) {
            // 400 tells Stripe not to keep retrying a request we will never accept.
            log.warn("Rejected a Stripe webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body("rejected");

        } catch (RuntimeException e) {
            // 500 makes Stripe retry, which is what we want for a transient fault.
            log.error("Failed to process a Stripe webhook", e);
            return ResponseEntity.internalServerError().body("error");
        }
    }
}

package com.chalkline.web;

import com.chalkline.config.AppProperties;
import com.chalkline.domain.Organisation;
import com.chalkline.domain.User;
import com.chalkline.repo.OrganisationRepository;
import com.chalkline.service.OrganisationService;
import com.chalkline.service.UserService;
import com.chalkline.service.ValidationException;
import com.chalkline.web.form.Forms;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Signing in, signing an institution up, and lecturers joining one that
 * already exists.
 *
 * Spring Security handles the sign-in POST; this only renders the page.
 */
@Controller
public class AuthController {

    private final UserService userService;
    private final OrganisationService organisationService;
    private final OrganisationRepository organisations;
    private final AppProperties properties;

    public AuthController(UserService userService,
                          OrganisationService organisationService,
                          OrganisationRepository organisations,
                          AppProperties properties) {
        this.userService = userService;
        this.organisationService = organisationService;
        this.organisations = organisations;
        this.properties = properties;
    }

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    // ---- New institution ----

    @GetMapping("/signup")
    public String signUpForm(Model model, RedirectAttributes redirectAttributes) {
        if (!properties.security().allowSignUp()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "New accounts are created by the administrator of this deployment. Contact "
                            + properties.supportEmail() + ".");
            return "redirect:/login";
        }
        model.addAttribute("form", new Forms.SignUp());
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String signUp(@ModelAttribute("form") Forms.SignUp form,
                         Model model,
                         RedirectAttributes redirectAttributes) {

        if (!properties.security().allowSignUp()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Sign-up is closed on this deployment.");
            return "redirect:/login";
        }

        try {
            User admin = organisationService.signUp(
                    form.getOrganisationName(), form.getDisplayName(),
                    form.getEmail(), form.getPassword(),
                    properties.security().minPasswordLength());

            if (properties.demo().seedStarterCatalogue()) {
                organisationService.seedStarterCatalogue(admin.getOrganisation());
            }
        } catch (ValidationException e) {
            // Re-render rather than redirect, so what they typed survives.
            model.addAttribute("errorMessage", e.getMessage());
            return "auth/signup";
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "Your institution is set up. Sign in to start building timetables.");
        return "redirect:/login";
    }

    // ---- Lecturer joining an existing institution ----

    @GetMapping("/join/{slug}")
    public String joinForm(@PathVariable String slug, Model model, RedirectAttributes redirectAttributes) {
        Organisation organisation = organisations.findBySlug(slug).orElse(null);

        if (organisation == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "That invitation link is not valid.");
            return "redirect:/login";
        }
        if (!organisation.isAllowSelfRegistration()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    organisation.getName() + " creates accounts centrally. Ask an administrator to add you.");
            return "redirect:/login";
        }

        model.addAttribute("organisation", organisation);
        model.addAttribute("form", new Forms.Join());
        return "auth/join";
    }

    @PostMapping("/join/{slug}")
    public String join(@PathVariable String slug,
                       @ModelAttribute("form") Forms.Join form,
                       Model model,
                       RedirectAttributes redirectAttributes) {

        Organisation organisation = organisations.findBySlug(slug).orElse(null);
        if (organisation == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "That invitation link is not valid.");
            return "redirect:/login";
        }

        try {
            userService.registerLecturer(organisation, form.getEmail(),
                    form.getDisplayName(), form.getPassword());
        } catch (RuntimeException e) {
            model.addAttribute("organisation", organisation);
            model.addAttribute("errorMessage", e.getMessage());
            return "auth/join";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Account created. Sign in to see your timetable.");
        return "redirect:/login";
    }
}

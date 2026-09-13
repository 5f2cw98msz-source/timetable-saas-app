package com.chalkline.web;

import com.chalkline.config.AppProperties;
import com.chalkline.domain.*;
import com.chalkline.security.AppUserPrincipal;
import com.chalkline.service.*;
import com.chalkline.web.form.Forms;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything under Settings.
 *
 * Routes under /settings/organisation, /team, /billing, /integrations,
 * /api-keys and /audit already require the ADMIN role (SecurityConfig), so
 * those methods do not repeat the check. The account pages are open to any
 * signed-in user, because they only ever touch their own record.
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final CurrentUser currentUser;
    private final UserService userService;
    private final OrganisationService organisationService;
    private final CatalogService catalogService;
    private final ShareLinkService shareLinkService;
    private final ApiKeyService apiKeyService;
    private final WebhookService webhookService;
    private final AuditService auditService;
    private final PlanPolicy planPolicy;
    private final AppProperties properties;

    public SettingsController(CurrentUser currentUser,
                              UserService userService,
                              OrganisationService organisationService,
                              CatalogService catalogService,
                              ShareLinkService shareLinkService,
                              ApiKeyService apiKeyService,
                              WebhookService webhookService,
                              AuditService auditService,
                              PlanPolicy planPolicy,
                              AppProperties properties) {
        this.currentUser = currentUser;
        this.userService = userService;
        this.organisationService = organisationService;
        this.catalogService = catalogService;
        this.shareLinkService = shareLinkService;
        this.apiKeyService = apiKeyService;
        this.webhookService = webhookService;
        this.auditService = auditService;
        this.planPolicy = planPolicy;
        this.properties = properties;
    }

    @GetMapping
    public String index() {
        return "redirect:/settings/account";
    }

    // ================= Account (any signed-in user) =================

    @GetMapping("/account")
    public String account(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = currentUser.require(principal);

        Forms.Profile form = new Forms.Profile();
        form.setDisplayName(user.getDisplayName());
        form.setNotifyOnChange(user.isNotifyOnChange());

        model.addAttribute("form", form);
        model.addAttribute("user", user);
        model.addAttribute("calendarUrl", calendarUrl(user));
        model.addAttribute("hasCalendarFeed",
                user.getOrganisation().hasFeature(Feature.CALENDAR_FEED));
        return "settings/account";
    }

    @PostMapping("/account")
    public String updateAccount(@AuthenticationPrincipal AppUserPrincipal principal,
                                @ModelAttribute("form") Forms.Profile form,
                                RedirectAttributes redirectAttributes) {

        userService.updateProfile(currentUser.require(principal),
                form.getDisplayName(), form.isNotifyOnChange());
        redirectAttributes.addFlashAttribute("successMessage", "Your details have been saved.");
        return "redirect:/settings/account";
    }

    @PostMapping("/account/calendar-token")
    public String regenerateCalendarToken(@AuthenticationPrincipal AppUserPrincipal principal,
                                          RedirectAttributes redirectAttributes) {

        userService.regenerateCalendarToken(currentUser.require(principal));
        redirectAttributes.addFlashAttribute("successMessage",
                "New calendar link created. The old one has stopped working, so re-subscribe in your calendar app.");
        return "redirect:/settings/account";
    }

    @GetMapping("/password")
    public String passwordForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("form", new Forms.Password());
        model.addAttribute("forced", principal.isMustChangePassword());
        return "settings/password";
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
                                 @ModelAttribute("form") Forms.Password form,
                                 HttpServletRequest request,
                                 Model model,
                                 RedirectAttributes redirectAttributes) throws ServletException {
        try {
            userService.changePassword(principal.getUsername(), form.getCurrentPassword(),
                    form.getNewPassword(), form.getConfirmPassword());
        } catch (ValidationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("forced", principal.isMustChangePassword());
            return "settings/password";
        }

        // End the session so the old one cannot be replayed and the
        // must-change flag is re-read fresh at the next sign-in.
        request.logout();
        redirectAttributes.addFlashAttribute("successMessage",
                "Password changed. Sign in again with your new password.");
        return "redirect:/login";
    }

    // ================= Organisation (admin) =================

    @GetMapping("/organisation")
    public String organisation(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Organisation organisation = currentUser.organisationOf(principal);

        Forms.OrganisationProfile profile = new Forms.OrganisationProfile();
        profile.setName(organisation.getName());
        profile.setAccentColour(organisation.getAccentColour());

        Forms.Scheduling scheduling = new Forms.Scheduling();
        scheduling.setDays(organisation.getScheduleDays());
        scheduling.setSlots(organisation.getScheduleSlots());
        scheduling.setPreventRoomClashes(organisation.isPreventRoomClashes());
        scheduling.setAllowSelfRegistration(organisation.isAllowSelfRegistration());

        model.addAttribute("organisation", organisation);
        model.addAttribute("profileForm", profile);
        model.addAttribute("schedulingForm", scheduling);
        model.addAttribute("joinUrl", absolute("/join/" + organisation.getSlug()));
        model.addAttribute("canBrand", organisation.hasFeature(Feature.CUSTOM_BRANDING));
        return "settings/organisation";
    }

    @PostMapping("/organisation/profile")
    public String updateOrganisation(@AuthenticationPrincipal AppUserPrincipal principal,
                                     @ModelAttribute Forms.OrganisationProfile form,
                                     RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        organisationService.updateProfile(actor.getOrganisation(), form.getName(),
                form.getAccentColour(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Institution details saved.");
        return "redirect:/settings/organisation";
    }

    @PostMapping("/organisation/scheduling")
    public String updateScheduling(@AuthenticationPrincipal AppUserPrincipal principal,
                                   @ModelAttribute Forms.Scheduling form,
                                   RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        organisationService.updateScheduling(actor.getOrganisation(), form.getDays(), form.getSlots(),
                form.isPreventRoomClashes(), form.isAllowSelfRegistration(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Timetable settings saved.");
        return "redirect:/settings/organisation";
    }

    // ================= Team (admin) =================

    @GetMapping("/team")
    public String team(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Organisation organisation = currentUser.organisationOf(principal);
        List<User> members = userService.findMembers(organisation);

        Map<Long, Long> classCounts = new LinkedHashMap<>();
        for (User member : members) {
            classCounts.put(member.getId(), userService.countClassesFor(member));
        }

        model.addAttribute("organisation", organisation);
        model.addAttribute("members", members);
        model.addAttribute("classCounts", classCounts);
        model.addAttribute("roles", Role.values());
        model.addAttribute("form", new Forms.Member());
        long staffUsed = planPolicy.staffUsed(organisation);
        model.addAttribute("staffUsed", staffUsed);
        model.addAttribute("staffLimitReached", planPolicy.staffLimitReached(organisation));
        model.addAttribute("staffPercent", staffMeterPercent(organisation, staffUsed));
        model.addAttribute("joinUrl", absolute("/join/" + organisation.getSlug()));
        return "settings/team";
    }

    @PostMapping("/team")
    public String addMember(@AuthenticationPrincipal AppUserPrincipal principal,
                            @ModelAttribute("form") Forms.Member form,
                            RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        userService.addMember(actor.getOrganisation(), form.getEmail(), form.getDisplayName(),
                form.getPassword(), form.getRole(), actor);

        redirectAttributes.addFlashAttribute("successMessage",
                form.getDisplayName() + " has been added. They will choose their own password at first sign-in.");
        return "redirect:/settings/team";
    }

    @PostMapping("/team/{id}/delete")
    public String deleteMember(@AuthenticationPrincipal AppUserPrincipal principal,
                               @PathVariable Long id,
                               RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        userService.delete(id, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage",
                "Account removed, along with any classes it had scheduled.");
        return "redirect:/settings/team";
    }

    @PostMapping("/team/{id}/enabled")
    public String setMemberEnabled(@AuthenticationPrincipal AppUserPrincipal principal,
                                   @PathVariable Long id,
                                   @RequestParam boolean enabled,
                                   RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        userService.setEnabled(id, enabled, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage",
                enabled ? "Account reactivated." : "Account suspended. Their timetable is kept.");
        return "redirect:/settings/team";
    }

    @PostMapping("/team/{id}/role")
    public String setMemberRole(@AuthenticationPrincipal AppUserPrincipal principal,
                                @PathVariable Long id,
                                @RequestParam Role role,
                                RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        userService.changeRole(id, role, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Role updated.");
        return "redirect:/settings/team";
    }

    @PostMapping("/team/{id}/reset-password")
    public String resetMemberPassword(@AuthenticationPrincipal AppUserPrincipal principal,
                                      @PathVariable Long id,
                                      @RequestParam String newPassword,
                                      RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        userService.resetPassword(id, newPassword, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage",
                "Password reset. Tell them the new one; they will be asked to change it at next sign-in.");
        return "redirect:/settings/team";
    }

    // ================= Rooms and courses =================

    @GetMapping("/catalogue")
    public String catalogue(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Organisation organisation = currentUser.organisationOf(principal);

        model.addAttribute("organisation", organisation);
        model.addAttribute("rooms", catalogService.findRooms(organisation));
        model.addAttribute("courses", catalogService.findCourses(organisation));
        model.addAttribute("roomForm", new Forms.RoomEntry());
        model.addAttribute("courseForm", new Forms.CourseEntry());
        model.addAttribute("canImport", organisation.hasFeature(Feature.CSV_IMPORT));
        return "settings/catalogue";
    }

    @PostMapping("/catalogue/rooms")
    public String addRoom(@AuthenticationPrincipal AppUserPrincipal principal,
                          @ModelAttribute Forms.RoomEntry form,
                          RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        catalogService.addRoom(actor.getOrganisation(), form.getName(), form.getCapacity(),
                form.getFacilities(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Room added.");
        return "redirect:/settings/catalogue";
    }

    @PostMapping("/catalogue/rooms/{id}/delete")
    public String deleteRoom(@AuthenticationPrincipal AppUserPrincipal principal,
                             @PathVariable Long id,
                             RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        catalogService.deleteRoom(id, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Room deleted.");
        return "redirect:/settings/catalogue";
    }

    @PostMapping("/catalogue/courses")
    public String addCourse(@AuthenticationPrincipal AppUserPrincipal principal,
                            @ModelAttribute Forms.CourseEntry form,
                            RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        catalogService.addCourse(actor.getOrganisation(), form.getCode(), form.getName(),
                form.getExpectedStudents(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Course added.");
        return "redirect:/settings/catalogue";
    }

    @PostMapping("/catalogue/courses/{id}/delete")
    public String deleteCourse(@AuthenticationPrincipal AppUserPrincipal principal,
                               @PathVariable Long id,
                               RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        catalogService.deleteCourse(id, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Course deleted.");
        return "redirect:/settings/catalogue";
    }

    @PostMapping("/catalogue/import")
    public String importCatalogue(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @RequestParam String type,
                                  @RequestParam String csv,
                                  RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        Organisation organisation = actor.getOrganisation();
        planPolicy.require(organisation, Feature.CSV_IMPORT);

        CatalogService.ImportResult result = "rooms".equals(type)
                ? catalogService.importRooms(organisation, csv, actor)
                : catalogService.importCourses(organisation, csv, actor);

        redirectAttributes.addFlashAttribute("successMessage", result.summary());
        return "redirect:/settings/catalogue";
    }

    // ================= Integrations (admin) =================

    @GetMapping("/integrations")
    public String integrations(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User actor = currentUser.require(principal);
        Organisation organisation = actor.getOrganisation();

        model.addAttribute("organisation", organisation);
        model.addAttribute("shareLinks", shareLinkService.findAll(organisation));
        model.addAttribute("webhooks", webhookService.findAll(organisation));
        model.addAttribute("lecturers", userService.findLecturers(organisation));
        model.addAttribute("shareForm", new Forms.Share());
        model.addAttribute("calendarUrl", calendarUrl(actor));
        model.addAttribute("baseUrl", properties.publicUrl());
        model.addAttribute("canShare", organisation.hasFeature(Feature.PUBLIC_SHARING));
        model.addAttribute("canWebhook", organisation.hasFeature(Feature.WEBHOOKS));
        model.addAttribute("canCalendar", organisation.hasFeature(Feature.CALENDAR_FEED));
        model.addAttribute("canLms", organisation.hasFeature(Feature.LMS_SYNC));
        return "settings/integrations";
    }

    @PostMapping("/integrations/share")
    public String createShare(@AuthenticationPrincipal AppUserPrincipal principal,
                              @ModelAttribute Forms.Share form,
                              RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        Organisation organisation = actor.getOrganisation();

        User lecturer = form.getScope() == ShareLink.Scope.SINGLE_LECTURER
                && form.getLecturerEmail() != null && !form.getLecturerEmail().isBlank()
                ? userService.requireMemberByEmail(form.getLecturerEmail(), organisation)
                : null;

        shareLinkService.create(organisation, form.getLabel(), form.getScope(), lecturer, actor);
        redirectAttributes.addFlashAttribute("successMessage", "Public link created.");
        return "redirect:/settings/integrations";
    }

    @PostMapping("/integrations/share/{id}/enabled")
    public String toggleShare(@AuthenticationPrincipal AppUserPrincipal principal,
                              @PathVariable Long id,
                              @RequestParam boolean enabled,
                              RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        shareLinkService.setEnabled(id, enabled, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage",
                enabled ? "Link switched back on." : "Link switched off. It now shows nothing.");
        return "redirect:/settings/integrations";
    }

    @PostMapping("/integrations/share/{id}/delete")
    public String deleteShare(@AuthenticationPrincipal AppUserPrincipal principal,
                              @PathVariable Long id,
                              RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        shareLinkService.delete(id, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Link deleted.");
        return "redirect:/settings/integrations";
    }

    @PostMapping("/integrations/webhooks")
    public String addWebhook(@AuthenticationPrincipal AppUserPrincipal principal,
                             @RequestParam String url,
                             RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        Organisation organisation = actor.getOrganisation();
        planPolicy.require(organisation, Feature.WEBHOOKS);

        webhookService.add(organisation, url);
        redirectAttributes.addFlashAttribute("successMessage",
                "Webhook added. Every timetable change will be posted to it, signed with its secret.");
        return "redirect:/settings/integrations";
    }

    @PostMapping("/integrations/webhooks/{id}/delete")
    public String deleteWebhook(@AuthenticationPrincipal AppUserPrincipal principal,
                                @PathVariable Long id,
                                RedirectAttributes redirectAttributes) {

        webhookService.delete(id, currentUser.organisationOf(principal));
        redirectAttributes.addFlashAttribute("successMessage", "Webhook removed.");
        return "redirect:/settings/integrations";
    }

    // ================= API keys (admin) =================

    @GetMapping("/api-keys")
    public String apiKeys(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Organisation organisation = currentUser.organisationOf(principal);

        model.addAttribute("organisation", organisation);
        model.addAttribute("keys", apiKeyService.findAll(organisation));
        model.addAttribute("canUseApi", organisation.hasFeature(Feature.API_ACCESS));
        model.addAttribute("baseUrl", properties.publicUrl());
        return "settings/api-keys";
    }

    @PostMapping("/api-keys")
    public String createApiKey(@AuthenticationPrincipal AppUserPrincipal principal,
                               @RequestParam String name,
                               RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        String plainKey = apiKeyService.create(actor.getOrganisation(), name, actor);

        // Shown once. There is no way to retrieve it again by design.
        redirectAttributes.addFlashAttribute("newApiKey", plainKey);
        return "redirect:/settings/api-keys";
    }

    @PostMapping("/api-keys/{id}/revoke")
    public String revokeApiKey(@AuthenticationPrincipal AppUserPrincipal principal,
                               @PathVariable Long id,
                               RedirectAttributes redirectAttributes) {

        User actor = currentUser.require(principal);
        apiKeyService.revoke(id, actor.getOrganisation(), actor);
        redirectAttributes.addFlashAttribute("successMessage", "Key revoked. It stops working immediately.");
        return "redirect:/settings/api-keys";
    }

    // ================= Audit log (admin) =================

    @GetMapping("/audit")
    public String audit(@AuthenticationPrincipal AppUserPrincipal principal,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {

        Organisation organisation = currentUser.organisationOf(principal);
        planPolicy.require(organisation, Feature.AUDIT_LOG);

        model.addAttribute("organisation", organisation);
        model.addAttribute("events", auditService.recent(organisation, Math.max(0, page), 50));
        model.addAttribute("page", Math.max(0, page));
        return "settings/audit";
    }

    // ---- helpers ----

    /**
     * How full the staff-account meter should look.
     *
     * Worked out here rather than in the template: Thymeleaf attribute
     * expressions cannot span lines, and arithmetic in a page is awkward to
     * test and easy to get wrong.
     */
    private int staffMeterPercent(Organisation organisation, long staffUsed) {
        int limit = organisation.getEffectivePlan().getStaffLimit();
        if (organisation.getEffectivePlan().isUnlimitedStaff() || limit <= 0) {
            return 12; // A token sliver, so the bar does not read as empty.
        }
        return (int) Math.min(100, Math.round(staffUsed * 100.0 / limit));
    }

    private String calendarUrl(User user) {
        return user.getCalendarToken() == null
                ? null
                : absolute("/feed/lecturer/" + user.getCalendarToken() + ".ics");
    }

    private String absolute(String path) {
        String base = properties.publicUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }
}

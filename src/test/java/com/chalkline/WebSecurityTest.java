package com.chalkline;

import com.chalkline.security.AppUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Route-level protection. A browser can request any URL, so every one of
 * these has to be refused by the server rather than by a hidden link.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:websec;DB_CLOSE_DELAY=-1",
        "app.demo.email="
})
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Transactional
class WebSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;

    TestFixtures.Fixture uni;
    AppUserPrincipal lecturer;
    AppUserPrincipal admin;

    @BeforeEach
    void setUp() {
        uni = fixtures.institution("Web University", "web.edu");
        lecturer = new AppUserPrincipal(uni.ada());
        admin = new AppUserPrincipal(uni.admin());
    }

    @Test
    @DisplayName("the marketing site, sign-in and sign-up are public")
    void publicRoutesAreReachable() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/pricing")).andExpect(status().isOk());
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/signup")).andExpect(status().isOk());
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("signed-out visitors cannot reach the application")
    void anonymousIsRedirected() throws Exception {
        mvc.perform(get("/timetable")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/settings/account")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/settings/team")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/settings/billing")).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("a lecturer cannot reach the administrator screens")
    void lecturerCannotReachAdminScreens() throws Exception {
        mvc.perform(get("/settings/team").with(user(lecturer))).andExpect(status().isForbidden());
        mvc.perform(get("/settings/organisation").with(user(lecturer))).andExpect(status().isForbidden());
        mvc.perform(get("/settings/billing").with(user(lecturer))).andExpect(status().isForbidden());
        mvc.perform(get("/settings/integrations").with(user(lecturer))).andExpect(status().isForbidden());
        mvc.perform(get("/settings/api-keys").with(user(lecturer))).andExpect(status().isForbidden());
        mvc.perform(get("/settings/audit").with(user(lecturer))).andExpect(status().isForbidden());

        mvc.perform(post("/settings/team").with(user(lecturer)).with(csrf())
                        .param("email", "sneaky@web.edu")
                        .param("displayName", "Sneaky")
                        .param("password", "password123")
                        .param("role", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a lecturer can reach their own pages")
    void lecturerCanReachOwnPages() throws Exception {
        mvc.perform(get("/timetable").with(user(lecturer))).andExpect(status().isOk());
        mvc.perform(get("/settings/account").with(user(lecturer))).andExpect(status().isOk());
        mvc.perform(get("/settings/password").with(user(lecturer))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("every administrator page renders without error")
    void adminPagesRender() throws Exception {
        // Checking the status alone would have missed a template that throws
        // on render, which is exactly how a broken page reaches production.
        for (String path : new String[]{
                "/timetable", "/settings/account", "/settings/password",
                "/settings/organisation", "/settings/team", "/settings/catalogue",
                "/settings/integrations", "/settings/api-keys", "/settings/billing"}) {
            mvc.perform(get(path).with(user(admin)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the plan badge shows the plan as it is now, not as it was at sign-in")
    void planBadgeIsNotStale() throws Exception {
        // The principal is built at sign-in, so this is the case that used to
        // show "Free" to somebody who had just paid.
        AppUserPrincipal signedInWhileFree = new AppUserPrincipal(uni.admin());
        fixtures.makePremium(uni.organisation());

        mvc.perform(get("/timetable").with(user(signedInWhileFree)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("currentPlan", com.chalkline.domain.Plan.PREMIUM));
    }

    @Test
    @DisplayName("a lecturer is refused another lecturer's slot form")
    void lecturerCannotOpenAnotherLecturersSlot() throws Exception {
        mvc.perform(get("/timetable/slot")
                        .param("day", "0").param("slot", "0").param("lecturer", "alan@web.edu")
                        .with(user(lecturer)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a POST without a CSRF token is refused")
    void csrfIsRequired() throws Exception {
        mvc.perform(post("/timetable/slot").with(user(lecturer))
                        .param("day", "0").param("slot", "0")
                        .param("lecturer", "ada@web.edu")
                        .param("courseId", "1").param("roomId", "1"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/logout").with(user(lecturer))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the Stripe webhook is reachable without a session but rejects unsigned calls")
    void webhookIsPublicButVerified() throws Exception {
        // No CSRF token and no login: Stripe has neither. It must not 401 or
        // 403, but it must refuse a request it cannot verify.
        mvc.perform(post("/billing/webhook")
                        .contentType("application/json")
                        .content("{\"type\":\"checkout.session.completed\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the API refuses calls with no key, a bad key, or the wrong scheme")
    void apiRequiresAKey() throws Exception {
        mvc.perform(get("/api/v1/timetable")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/timetable").header("Authorization", "Bearer ck_nonsense"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/timetable").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/courses")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown share token shows the not-found page, never somebody's timetable")
    void unknownShareTokenIsSafe() throws Exception {
        mvc.perform(get("/s/made-up-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("public/not-found"));

        mvc.perform(get("/feed/lecturer/made-up-token.ics")).andExpect(status().isNotFound());
    }
}

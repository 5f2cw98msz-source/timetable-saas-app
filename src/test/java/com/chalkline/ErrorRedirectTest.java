package com.chalkline;

import com.chalkline.service.ValidationException;
import com.chalkline.web.GlobalErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a validation error the user goes back where they came from, which is
 * read out of the Referer header. Anyone can forge that header, so this checks
 * it can never become a redirect to another website.
 */
class ErrorRedirectTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    private String redirectFor(String referer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (referer != null) {
            request.addHeader("Referer", referer);
        }
        return handler.handleValidation(new ValidationException("something was wrong"),
                request, new RedirectAttributesModelMap());
    }

    @Test
    @DisplayName("the user goes back to the page they were on")
    void returnsToTheSamePage() {
        assertThat(redirectFor("http://localhost:8080/settings/team"))
                .isEqualTo("redirect:/settings/team");
        assertThat(redirectFor("https://chalkline.app/timetable?lecturer=ada@x.edu"))
                .isEqualTo("redirect:/timetable?lecturer=ada@x.edu");
        assertThat(redirectFor("/settings/catalogue")).isEqualTo("redirect:/settings/catalogue");
    }

    @Test
    @DisplayName("a forged Referer cannot redirect the user off this site")
    void cannotBeUsedAsAnOpenRedirect() {
        // The host is discarded, so an attacker's URL collapses to a local path.
        assertThat(redirectFor("https://evil.example.com/phishing")).isEqualTo("redirect:/phishing");
        assertThat(redirectFor("//evil.example.com/phishing")).isEqualTo("redirect:/phishing");

        // A path of its own beginning with "//" is read by browsers as
        // protocol-relative, so it would leave the site. Must fall back.
        assertThat(redirectFor("http://localhost:8080//evil.example.com"))
                .isEqualTo("redirect:/timetable");
        assertThat(redirectFor("https://x.example.com//attacker.net/p"))
                .isEqualTo("redirect:/timetable");

        assertThat(redirectFor("javascript:alert(1)")).isEqualTo("redirect:/timetable");
        assertThat(redirectFor("h ttp://broken")).isEqualTo("redirect:/timetable");
        assertThat(redirectFor("")).isEqualTo("redirect:/timetable");
        assertThat(redirectFor(null)).isEqualTo("redirect:/timetable");
    }

    @Test
    @DisplayName("the message the user sees is the one the service raised")
    void carriesTheMessage() {
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();
        handler.handleValidation(new ValidationException("Lab 1 is already booked."),
                new MockHttpServletRequest(), attributes);

        assertThat(attributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Lab 1 is already booked.");
    }
}

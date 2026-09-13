package com.chalkline.web;

import com.chalkline.service.AccessDeniedForLecturerException;
import com.chalkline.service.UpgradeRequiredException;
import com.chalkline.service.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.net.URISyntaxException;

/** Turns service exceptions into something a person can read and act on. */
@ControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private static final String FALLBACK = "/timetable";

    /** A problem the user can fix. Red banner, back on the page they came from. */
    @ExceptionHandler(ValidationException.class)
    public String handleValidation(ValidationException e, HttpServletRequest request,
                                   RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        return "redirect:" + safeReturnPath(request.getHeader("Referer"));
    }

    /**
     * The plan does not include this. Deliberately NOT an error page: it is a
     * sales moment, so the user is sent back with an explanation and the
     * billing page one click away.
     */
    @ExceptionHandler(UpgradeRequiredException.class)
    public String handleUpgradeRequired(UpgradeRequiredException e, HttpServletRequest request,
                                        RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("upgradeMessage", e.getMessage());
        return "redirect:" + safeReturnPath(request.getHeader("Referer"));
    }

    /** A lecturer reaching for somebody else's timetable. Logged, then refused. */
    @ExceptionHandler(AccessDeniedForLecturerException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleForbidden(AccessDeniedForLecturerException e, HttpServletRequest request) {
        log.warn("Refused cross-lecturer access: user={} path={} reason={}",
                request.getRemoteUser(), request.getRequestURI(), e.getMessage());
        return "error/forbidden";
    }

    /**
     * Where to send the user back to, from the Referer header.
     *
     * Anyone can forge that header, so only the PATH and QUERY are used and
     * the host is discarded. A path of its own beginning with "//" is read by
     * browsers as protocol-relative, so that falls back too -- otherwise an
     * error message could become a redirect to somebody else's site.
     */
    private String safeReturnPath(String referer) {
        if (referer == null || referer.isBlank()) {
            return FALLBACK;
        }
        try {
            URI uri = new URI(referer);
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return FALLBACK;
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (URISyntaxException e) {
            return FALLBACK;
        }
    }
}

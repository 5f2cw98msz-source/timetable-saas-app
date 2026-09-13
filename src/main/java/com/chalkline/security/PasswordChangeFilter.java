package com.chalkline.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Accounts created with somebody else's chosen password are pinned to the
 * change-password page until they set their own. Without this, a temporary
 * password handed out by an administrator would stay valid indefinitely.
 */
@Component
public class PasswordChangeFilter extends OncePerRequestFilter {

    private static final String CHANGE_PASSWORD_PATH = "/settings/password";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal
                && principal.isMustChangePassword() && !isAllowed(request)) {
            response.sendRedirect(request.getContextPath() + CHANGE_PASSWORD_PATH);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Assets, the change-password page itself, and signing out stay reachable. */
    private boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(CHANGE_PASSWORD_PATH)
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.equals("/favicon.svg")
                || path.equals("/logout")
                || path.equals("/error");
    }
}

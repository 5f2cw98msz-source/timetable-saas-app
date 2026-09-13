package com.chalkline.config;

import com.chalkline.security.PasswordChangeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Who can reach what.
 *
 * Every route is listed here rather than trusted to the page that renders it.
 * On the web anyone can type a URL, so a hidden link is not a permission.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * BCrypt, via Spring's delegating encoder, so hashes carry a "{bcrypt}"
     * prefix and the algorithm can be upgraded later without invalidating
     * every existing password.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, PasswordChangeFilter passwordChangeFilter)
            throws Exception {

        http
            .authorizeHttpRequests(auth -> auth
                    // Marketing site and assets.
                    .requestMatchers("/", "/pricing", "/features", "/legal/**").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.svg").permitAll()

                    // Sign in and sign up.
                    .requestMatchers("/login", "/signup", "/join/**", "/error").permitAll()

                    // Public read-only pages and calendar feeds. Both are
                    // protected by an unguessable token in the URL, not by a
                    // session, because the people using them have no account.
                    .requestMatchers("/s/**", "/feed/**").permitAll()

                    // Stripe posts here. Authenticated by signature, not session.
                    .requestMatchers("/billing/webhook").permitAll()

                    .requestMatchers("/actuator/health").permitAll()

                    // The API authenticates with a key, checked in its own filter.
                    .requestMatchers("/api/**").permitAll()

                    .requestMatchers("/settings/organisation", "/settings/team/**",
                                     "/settings/billing/**", "/settings/integrations/**",
                                     "/settings/api-keys/**", "/settings/audit").hasRole("ADMIN")

                    .anyRequest().authenticated()
            )
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    // Spring Security's "username" parameter carries the email.
                    .usernameParameter("username")
                    .defaultSuccessUrl("/timetable", true)
                    .failureUrl("/login?error")
                    .permitAll()
            )
            .logout(logout -> logout
                    .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                    .logoutSuccessUrl("/?loggedOut")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll()
            )
            .addFilterAfter(passwordChangeFilter, AuthorizationFilter.class)

            .csrf(csrf -> csrf
                    // Stripe cannot send a CSRF token, and the API is
                    // stateless and key-authenticated, so neither is
                    // vulnerable to a browser being tricked into a request.
                    .ignoringRequestMatchers("/billing/webhook", "/api/**")
            )
            .sessionManagement(session -> session
                    .sessionFixation().migrateSession()
            )
            .headers(headers -> headers
                    .frameOptions(frame -> frame.deny())
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; img-src 'self' data:; style-src 'self'; "
                                    + "script-src 'self'; form-action 'self' https://checkout.stripe.com; "
                                    + "frame-ancestors 'none'; base-uri 'self'"))
            );

        return http.build();
    }
}
